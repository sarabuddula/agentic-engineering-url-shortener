package com.agenticengineering.urlshortener.orchestration.replan;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dynamic replanning (T072; FR-029, FR-046, D-006). Scope per L-SC-03 (fourth governed change): no staged-revision
 * dispatch and no HELD nodes; the current revision keeps running until a human accepts.
 *
 * <p>A request re-validates every artifact the current revision's stages read (NODE_INPUT) against the latest version
 * by content hash. Changed inputs are INVALIDATED, as are the approvals and policy evidence of the affected nodes (the
 * changed nodes and everything downstream of them); the event is STAGED as run_revision + 1 and a REPLAN_ACCEPTANCE
 * approval is requested. Acceptance by an APPROVER other than the requester activates the new revision with the affected
 * nodes to be redone; rejection or expiry leaves the prior revision in force.
 */
@Component
public class ReplanningService {

    private final JdbcTemplate jdbc;
    private final WorkflowStore store;
    private final ApprovalService approvals;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();
    private final DecisionLineage lineage;

    public ReplanningService(JdbcTemplate jdbc, WorkflowStore store, ApprovalService approvals, AuditChain audit, TimeSource clock,
            PlatformTransactionManager transactions, DecisionLineage lineage) {
        this.lineage = lineage;
        this.jdbc = jdbc;
        this.store = store;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Stages a replan; returns the REPLANNING_EVENT id. Throws ReplanConflictException when nothing can be replanned. */
    public long request(String runId, String requesterKeyId) {
        var status = store.runStatus(runId);
        if (status.isTerminal()) {
            // not audited: nothing is appended to a run's chain after its terminal verification (T086)
            throw new ReplanConflictException("run is terminal (" + status + ")");
        }
        Optional<Long> staged = stagedEvent(runId);
        if (staged.isPresent()) {
            throw refused(runId, requesterKeyId, "a replan acceptance is pending (event " + staged.get() + ")");
        }
        int revision = jdbc.queryForObject("SELECT RUN_REVISION FROM WORKFLOW_RUN WHERE ID = ?", Integer.class, runId);
        List<Map<String, Object>> inputs = new ArrayList<>();
        TreeSet<String> changedNodes = new TreeSet<>();
        for (Map<String, Object> read : jdbc.queryForList("SELECT n.NODE_KEY, a.TYPE, a.VERSION, i.CONTENT_HASH_AT_READ,"
                + " (SELECT b.VERSION FROM ARTIFACT b WHERE b.RUN_ID = a.RUN_ID AND b.TYPE = a.TYPE ORDER BY b.VERSION DESC LIMIT 1) AS LATEST_VERSION,"
                + " (SELECT b.CONTENT_HASH FROM ARTIFACT b WHERE b.RUN_ID = a.RUN_ID AND b.TYPE = a.TYPE ORDER BY b.VERSION DESC LIMIT 1) AS LATEST_HASH"
                + " FROM NODE_INPUT i JOIN STAGE_NODE n ON n.ID = i.NODE_ID JOIN ARTIFACT a ON a.ID = i.ARTIFACT_ID"
                + " WHERE n.RUN_ID = ? AND n.RUN_REVISION = ? ORDER BY n.NODE_KEY, a.TYPE", runId, revision)) {
            boolean valid = read.get("CONTENT_HASH_AT_READ").equals(read.get("LATEST_HASH"));
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("node", read.get("NODE_KEY"));
            record.put("artifact_type", read.get("TYPE"));
            record.put("version_read", read.get("VERSION"));
            record.put("hash_at_read", read.get("CONTENT_HASH_AT_READ"));
            record.put("current_version", read.get("LATEST_VERSION"));
            record.put("current_hash", read.get("LATEST_HASH"));
            record.put("status", valid ? "VALID" : "INVALIDATED");
            inputs.add(record);
            if (!valid) {
                changedNodes.add((String) read.get("NODE_KEY"));
            }
        }
        if (changedNodes.isEmpty()) {
            throw refused(runId, requesterKeyId, "no upstream change: every input read by revision " + revision
                    + " matches its latest version");
        }
        TreeSet<String> affected = downstream(SdlcWorkflow.graph(), changedNodes);
        List<Map<String, Object>> invalidatedApprovals = new ArrayList<>();
        for (String node : affected) {
            jdbc.query("SELECT ID, GATE, DECISION FROM APPROVAL WHERE RUN_ID = ? AND SCOPE = ? AND DECISION IN ('PENDING', 'ESCALATED', 'APPROVED')",
                    rs -> {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("approval_id", rs.getLong("ID"));
                        record.put("gate", rs.getString("GATE"));
                        record.put("node", node);
                        record.put("decision", rs.getString("DECISION"));
                        record.put("status", "INVALIDATED (re-approval required in the new revision)");
                        invalidatedApprovals.add(record);
                    }, runId, "node=" + node);
        }
        int evidence = affected.contains("N12")
                ? jdbc.queryForObject("SELECT COUNT(*) FROM POLICY_EVALUATION WHERE RUN_ID = ?", Integer.class, runId) : 0;
        Map<String, Object> records = new LinkedHashMap<>();
        records.put("inputs", inputs);
        records.put("approvals", invalidatedApprovals);
        records.put("policy_evidence", evidence == 0 ? "none recorded for the affected nodes"
                : evidence + " POLICY_EVALUATION rows INVALIDATED (N12 re-evaluates in the new revision)");
        String affectedList = String.join(",", affected);
        return tx.execute(s -> {
            jdbc.update("INSERT INTO REPLANNING_EVENT (RUN_ID, STATUS, STAGED_REVISION, TRIGGER_KIND, AFFECTED_DOWNSTREAM,"
                    + " INVALIDATION_RECORDS) VALUES (?, 'STAGED', ?, 'ARTIFACT_VERSION_CHANGE', ?, ?)", runId, revision + 1, affectedList,
                    write(records));
            long id = jdbc.queryForObject("SELECT MAX(ID) FROM REPLANNING_EVENT WHERE RUN_ID = ?", Long.class, runId);
            ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.REPLAN_ACCEPTANCE, "REPLAN", requesterKeyId,
                    "replan " + id + ": accept revision " + (revision + 1) + " redoing " + affectedList);
            jdbc.update("UPDATE REPLANNING_EVENT SET APPROVAL_ID = ? WHERE ID = ?", approval.id(), id);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "REPLAN_REQUESTED", clock.now(), "REPLAN", "STAGED",
                    "replan " + id + " by " + requesterKeyId + ": changed inputs at " + changedNodes + "; affected " + affectedList
                            + "; " + invalidatedApprovals.size() + " approval(s) invalidated; acceptance approval " + approval.id()));
            return id;
        });
    }

    /**
     * Settles a decided acceptance: APPROVED activates the staged revision (returns true, the run should be driven again);
     * REJECTED leaves the prior revision in force. Undecided: nothing happens.
     */
    public boolean settle(String runId) {
        Optional<Map<String, Object>> event = jdbc.queryForList("SELECT ID, STAGED_REVISION, AFFECTED_DOWNSTREAM, APPROVAL_ID FROM"
                + " REPLANNING_EVENT WHERE RUN_ID = ? AND STATUS = 'STAGED'", runId).stream().findFirst();
        if (event.isEmpty() || event.get().get("APPROVAL_ID") == null) {
            return false;
        }
        long id = ((Number) event.get().get("ID")).longValue();
        ApprovalService.Approval approval = approvals.find(runId, ((Number) event.get().get("APPROVAL_ID")).longValue());
        if (approval.status() == ApprovalService.Decision.APPROVED) {
            List<String> affected = List.of(((String) event.get().get("AFFECTED_DOWNSTREAM")).split(","));
            tx.executeWithoutResult(s -> {
                int revision = store.activateRevision(runId, affected, "replan " + id + " accepted by " + approval.approverKeyId());
                jdbc.update("UPDATE REPLANNING_EVENT SET STATUS = 'ACCEPTED' WHERE ID = ? AND STATUS = 'STAGED'", id);
                audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "REPLAN_ACCEPTED", clock.now(), "REPLAN", "ACCEPTED",
                        "replan " + id + " accepted by " + approval.approverKeyId() + " (approval " + approval.id() + "); revision "
                                + revision + " active; redoing " + affected));
                lineage.record(runId, "REPLAN", AuditEntry.ActorType.HUMAN, "replan " + id + " ACCEPTED by " + approval.approverKeyId()
                        + ": revision " + revision + " redoes " + affected, approval.reason(),
                        List.of("keep revision " + (revision - 1) + " with inputs that no longer match their latest versions"),
                        lineage.currentArtifacts(runId));
            });
            return true;
        }
        if (approval.status() == ApprovalService.Decision.REJECTED) {
            close(runId, id, "REJECTED", "REPLAN_REJECTED", "replan " + id + " rejected by " + approval.approverKeyId()
                    + "; the current revision stays in force");
            lineage.record(runId, "REPLAN", AuditEntry.ActorType.HUMAN, "replan " + id + " REJECTED by " + approval.approverKeyId()
                    + "; the current revision stays in force", approval.reason(),
                    List.of("accept the staged revision redoing " + event.get().get("AFFECTED_DOWNSTREAM")), lineage.currentArtifacts(runId));
        }
        return false;
    }

    /** The acceptance approval expired: the staged revision is discarded and the run continues under the prior one. */
    public void expire(String runId, long approvalId) {
        jdbc.queryForList("SELECT ID FROM REPLANNING_EVENT WHERE RUN_ID = ? AND APPROVAL_ID = ? AND STATUS = 'STAGED'", Long.class, runId,
                approvalId).forEach(id -> close(runId, id, "EXPIRED", "REPLAN_EXPIRED",
                        "replan " + id + " acceptance expired without a decision; silence is never acceptance; the current revision stays in force"));
    }

    private void close(String runId, long id, String status, String action, String reason) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE REPLANNING_EVENT SET STATUS = ? WHERE ID = ? AND STATUS = 'STAGED'", status, id);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, action, clock.now(), "REPLAN", status, reason));
        });
    }

    private ReplanConflictException refused(String runId, String requesterKeyId, String reason) {
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "REPLAN_REFUSED", clock.now(), "REPLAN", "REFUSED",
                "replan requested by " + requesterKeyId + " refused: " + reason));
        return new ReplanConflictException(reason);
    }

    private Optional<Long> stagedEvent(String runId) {
        return jdbc.queryForList("SELECT ID FROM REPLANNING_EVENT WHERE RUN_ID = ? AND STATUS = 'STAGED'", Long.class, runId).stream()
                .findFirst();
    }

    /** The given nodes and every node reachable from them over any edge. */
    static TreeSet<String> downstream(StageGraph graph, TreeSet<String> start) {
        TreeSet<String> reached = new TreeSet<>(start);
        Deque<String> queue = new ArrayDeque<>(start);
        while (!queue.isEmpty()) {
            String node = queue.pop();
            for (StageGraph.Edge edge : graph.edges()) {
                if (edge.from().equals(node) && reached.add(edge.to())) {
                    queue.push(edge.to());
                }
            }
        }
        return reached;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
