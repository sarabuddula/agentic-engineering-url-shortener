package com.agenticengineering.urlshortener.orchestration.persistence;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.IllegalTransitionException;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.StateMachines;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persisted run and node lifecycle (T053; FR-022, FR-025, FR-030, PVT-007; Spring JDBC per the 2026-10-21 amendment).
 * A legal transition is applied with an optimistic lock (expected status and version) and audited in the same
 * transaction, so state and evidence cannot diverge. An illegal attempt is audited as TRANSITION_BLOCKED before the
 * exception is raised, outside the rejected change, so the evidence survives (SC-004). Node rows are bound to the run
 * revision current at creation; entering RUNNING increments the attempt count.
 */
@Component
public class WorkflowStore {

    /** One audit row as returned by the history query (Constitution IX field set). */
    public record HistoryEntry(long seq, String actorType, String action, Instant occurredAt, String affected, String result,
            String reason) {
    }

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;

    public WorkflowStore(JdbcTemplate jdbc, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** A run's externally visible state (contract RunResponse without pending approvals). */
    public record RunDetail(String runId, RunStatus status, String policyVersion, int runRevision, String executionClass,
            String terminalOutcome) {
    }

    public void createRun(String runId, String requirementRef, String policyVersion, StageGraph graph) {
        createRun(runId, requirementRef, policyVersion, graph, "LIVE");
    }

    /** @param executionClass LIVE or REPLAY (REPLAY: the run exercises behaviour implemented before the run) */
    public void createRun(String runId, String requirementRef, String policyVersion, StageGraph graph, String executionClass) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.now();
            jdbc.update("INSERT INTO WORKFLOW_RUN (ID, REQUIREMENT_REF, POLICY_VERSION, STATUS, RUN_REVISION, VERSION,"
                    + " AUDIT_EVENT_COUNT, CREATED_AT, EXECUTION_CLASS, REPLAN_COUNT) VALUES (?, ?, ?, 'RUNNING', 0, 0, 0, ?, ?, 0)",
                    runId, requirementRef, policyVersion, Timestamp.from(now), executionClass);
            Map<String, Long> ids = new HashMap<>();
            for (String node : graph.nodes()) {
                jdbc.update("INSERT INTO STAGE_NODE (RUN_ID, NODE_KEY, STATUS, ATTEMPT_COUNT, RUN_REVISION, VERSION)"
                        + " VALUES (?, ?, 'PENDING', 0, 0, 0)", runId, node);
                ids.put(node, jdbc.queryForObject("SELECT ID FROM STAGE_NODE WHERE RUN_ID = ? AND NODE_KEY = ? AND RUN_REVISION = 0",
                        Long.class, runId, node));
            }
            for (StageGraph.Edge edge : graph.edges()) {
                jdbc.update("INSERT INTO STAGE_EDGE (RUN_ID, FROM_NODE, TO_NODE, KIND) VALUES (?, ?, ?, ?)", runId,
                        ids.get(edge.from()), ids.get(edge.to()), edge.kind().name());
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "RUN_CREATED", now, runId, "CREATED",
                    "run created with " + graph.nodes().size() + " nodes and " + graph.edges().size() + " edges; policy "
                            + policyVersion + "; execution class " + executionClass));
        });
    }

    public void transitionNode(String runId, String node, NodeStatus from, NodeStatus to, String reason) {
        transitionNode(runId, node, from, to, reason, AuditEntry.ActorType.SYSTEM);
    }

    public void transitionNode(String runId, String node, NodeStatus from, NodeStatus to, String reason,
            AuditEntry.ActorType actor) {
        if (!StateMachines.isLegalNode(from, to)) {
            auditBlocked(runId, node, from + "->" + to, reason, actor);
            throw new IllegalTransitionException(from, to);
        }
        tx.executeWithoutResult(status -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT n.ID, n.STATUS, n.VERSION FROM STAGE_NODE n JOIN WORKFLOW_RUN r"
                    + " ON r.ID = n.RUN_ID AND r.RUN_REVISION = n.RUN_REVISION WHERE n.RUN_ID = ? AND n.NODE_KEY = ?", runId, node);
            if (!from.name().equals(row.get("STATUS"))) {
                throw new StaleStateException(node + " is " + row.get("STATUS") + ", not " + from);
            }
            int updated = jdbc.update("UPDATE STAGE_NODE SET STATUS = ?, VERSION = VERSION + 1, ATTEMPT_COUNT = ATTEMPT_COUNT + ?"
                    + " WHERE ID = ? AND VERSION = ?", to.name(), to == NodeStatus.RUNNING ? 1 : 0, row.get("ID"), row.get("VERSION"));
            if (updated != 1) {
                throw new StaleStateException(node + " changed concurrently");
            }
            audit.append(runId, new AuditEntry(actor, "NODE_TRANSITION", clock.now(), node, from + "->" + to,
                    reason != null ? reason : defaultReason(to)));
        });
    }

    /** Safe-stops the run with a recorded cause (SAFE_STOP_CAUSE), for example COMPLETION_GATE or BOOT_RECOVERY. */
    public void safeStopRun(String runId, RunStatus from, String cause, String reason) {
        tx.executeWithoutResult(status -> {
            transitionRun(runId, from, RunStatus.SAFE_STOPPED, reason);
            jdbc.update("UPDATE WORKFLOW_RUN SET SAFE_STOP_CAUSE = ? WHERE ID = ?", cause, runId);
        });
    }

    public String safeStopCause(String runId) {
        return jdbc.queryForObject("SELECT SAFE_STOP_CAUSE FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
    }

    /** SAFE_STOPPED -> RUNNING with the cause cleared (authorized resumption, T110). */
    public void resumeRun(String runId, String reason) {
        tx.executeWithoutResult(status -> {
            transitionRun(runId, RunStatus.SAFE_STOPPED, RunStatus.RUNNING, reason);
            jdbc.update("UPDATE WORKFLOW_RUN SET SAFE_STOP_CAUSE = NULL WHERE ID = ?", runId);
        });
    }

    /** Pending operations of a type without an effect-verification rule (T122). */
    public int unverifiablePendingOperations(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM OPERATION_RECORD WHERE RUN_ID = ? AND STATUS = 'PENDING' AND TYPE NOT IN"
                + " ('ARTIFACT_WRITE', 'TEST_RUN', 'DOC_GENERATE', 'POLICY_EVAL', 'APPROVAL_REQUEST')", Integer.class, runId);
    }

    /** Records the run's terminal outcome (for example NOT_READY or READY) together with the terminal transition. */
    public void finishRun(String runId, RunStatus from, RunStatus to, String terminalOutcome, String reason) {
        tx.executeWithoutResult(status -> {
            transitionRun(runId, from, to, reason);
            jdbc.update("UPDATE WORKFLOW_RUN SET TERMINAL_OUTCOME = ? WHERE ID = ?", terminalOutcome, runId);
        });
    }

    /**
     * Activates run_revision + 1 (accepted replan, FR-029): new node rows and edges for the revision, the affected nodes
     * PENDING and every other node carried over with its status; returns the new revision.
     */
    public int activateRevision(String runId, java.util.Collection<String> affected, String reason) {
        return tx.execute(status -> {
            Map<String, Object> run = jdbc.queryForMap("SELECT RUN_REVISION, VERSION FROM WORKFLOW_RUN WHERE ID = ?", runId);
            int current = ((Number) run.get("RUN_REVISION")).intValue();
            int next = current + 1;
            Map<Long, Long> ids = new HashMap<>();
            for (Map<String, Object> node : jdbc.queryForList("SELECT ID, NODE_KEY, STATUS, NOT_APPLICABLE_REASON FROM STAGE_NODE"
                    + " WHERE RUN_ID = ? AND RUN_REVISION = ? ORDER BY ID", runId, current)) {
                String key = (String) node.get("NODE_KEY");
                boolean redo = affected.contains(key);
                jdbc.update("INSERT INTO STAGE_NODE (RUN_ID, NODE_KEY, STATUS, ATTEMPT_COUNT, RUN_REVISION, VERSION, NOT_APPLICABLE_REASON)"
                        + " VALUES (?, ?, ?, 0, ?, 0, ?)", runId, key, redo ? "PENDING" : node.get("STATUS"), next,
                        redo ? null : node.get("NOT_APPLICABLE_REASON"));
                ids.put(((Number) node.get("ID")).longValue(), jdbc.queryForObject(
                        "SELECT ID FROM STAGE_NODE WHERE RUN_ID = ? AND NODE_KEY = ? AND RUN_REVISION = ?", Long.class, runId, key, next));
            }
            for (Map<String, Object> edge : jdbc.queryForList("SELECT FROM_NODE, TO_NODE, KIND FROM STAGE_EDGE WHERE RUN_ID = ?", runId)) {
                Long from = ids.get(((Number) edge.get("FROM_NODE")).longValue());
                Long to = ids.get(((Number) edge.get("TO_NODE")).longValue());
                if (from != null && to != null) {
                    jdbc.update("INSERT INTO STAGE_EDGE (RUN_ID, FROM_NODE, TO_NODE, KIND) VALUES (?, ?, ?, ?)", runId, from, to, edge.get("KIND"));
                }
            }
            int updated = jdbc.update("UPDATE WORKFLOW_RUN SET RUN_REVISION = ?, REPLAN_COUNT = REPLAN_COUNT + 1, VERSION = VERSION + 1"
                    + " WHERE ID = ? AND VERSION = ?", next, runId, run.get("VERSION"));
            if (updated != 1) {
                throw new StaleStateException("run changed concurrently");
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "REVISION_ACTIVATED", clock.now(), runId,
                    "REVISION_" + next, reason + "; redo " + affected));
            return next;
        });
    }

    public void transitionRun(String runId, RunStatus from, RunStatus to, String reason) {
        if (!StateMachines.isLegalRun(from, to)) {
            auditBlocked(runId, runId, from + "->" + to, reason, AuditEntry.ActorType.SYSTEM);
            throw new IllegalTransitionException(from, to);
        }
        tx.executeWithoutResult(status -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT STATUS, VERSION FROM WORKFLOW_RUN WHERE ID = ?", runId);
            if (!from.name().equals(row.get("STATUS"))) {
                throw new StaleStateException("run is " + row.get("STATUS") + ", not " + from);
            }
            int updated = jdbc.update("UPDATE WORKFLOW_RUN SET STATUS = ?, VERSION = VERSION + 1 WHERE ID = ? AND VERSION = ?",
                    to.name(), runId, row.get("VERSION"));
            if (updated != 1) {
                throw new StaleStateException("run changed concurrently");
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "RUN_TRANSITION", clock.now(), runId,
                    from + "->" + to, reason != null ? reason : "run state change"));
        });
    }

    public java.util.Optional<RunDetail> runDetail(String runId) {
        List<RunDetail> rows = jdbc.query("SELECT ID, STATUS, POLICY_VERSION, RUN_REVISION, EXECUTION_CLASS, TERMINAL_OUTCOME"
                + " FROM WORKFLOW_RUN WHERE ID = ?", (rs, i) -> new RunDetail(rs.getString("ID"), RunStatus.valueOf(rs.getString("STATUS")),
                        rs.getString("POLICY_VERSION"), rs.getInt("RUN_REVISION"), rs.getString("EXECUTION_CLASS"),
                        rs.getString("TERMINAL_OUTCOME")), runId);
        return rows.stream().findFirst();
    }

    /** The run creator's key ID from requirement_ref "api:<key id>"; "system" for runs created without a key. */
    public String requesterKeyId(String runId) {
        String ref = jdbc.queryForObject("SELECT REQUIREMENT_REF FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
        return ref != null && ref.startsWith("api:") ? ref.substring(4) : "system";
    }

    /** Audits a progression attempt that a pending gate blocked (SC-004). */
    public void auditGateBlocked(String runId, String node, String reason) {
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "GATE_BLOCKED", clock.now(), node, "PENDING", reason));
    }

    /** The STAGE_NODE id of the node in the run's current revision. */
    public long nodeId(String runId, String node) {
        return jdbc.queryForObject("SELECT n.ID FROM STAGE_NODE n JOIN WORKFLOW_RUN r ON r.ID = n.RUN_ID AND r.RUN_REVISION = n.RUN_REVISION"
                + " WHERE n.RUN_ID = ? AND n.NODE_KEY = ?", Long.class, runId, node);
    }

    public RunStatus runStatus(String runId) {
        return RunStatus.valueOf(jdbc.queryForObject("SELECT STATUS FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId));
    }

    public Map<String, NodeStatus> nodeStatuses(String runId) {
        Map<String, NodeStatus> statuses = new LinkedHashMap<>();
        jdbc.query("SELECT n.NODE_KEY, n.STATUS FROM STAGE_NODE n JOIN WORKFLOW_RUN r ON r.ID = n.RUN_ID AND r.RUN_REVISION ="
                + " n.RUN_REVISION WHERE n.RUN_ID = ? ORDER BY n.ID", rs -> {
                    statuses.put(rs.getString("NODE_KEY"), NodeStatus.valueOf(rs.getString("STATUS")));
                }, runId);
        return statuses;
    }

    public List<HistoryEntry> history(String runId) {
        return jdbc.query("SELECT SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, AFFECTED_ARTIFACT, RESULT, REASON FROM AUDIT_EVENT"
                + " WHERE RUN_ID = ? ORDER BY SEQ", (rs, i) -> new HistoryEntry(rs.getLong("SEQ"), rs.getString("ACTOR_TYPE"),
                        rs.getString("ACTION"), rs.getObject("OCCURRED_AT", OffsetDateTime.class).toInstant(),
                        rs.getString("AFFECTED_ARTIFACT"), rs.getString("RESULT"), rs.getString("REASON")), runId);
    }

    private void auditBlocked(String runId, String affected, String attempted, String reason, AuditEntry.ActorType actor) {
        audit.append(runId, new AuditEntry(actor, "TRANSITION_BLOCKED", clock.now(), affected, attempted,
                "prohibited transition" + (reason != null ? ": " + reason : "")));
    }

    private static String defaultReason(NodeStatus to) {
        return switch (to) {
            case READY -> "entry criteria met: all predecessors satisfied";
            case RUNNING -> "dispatched to executor";
            case SUCCEEDED -> "exit criteria met";
            default -> "state change to " + to;
        };
    }
}
