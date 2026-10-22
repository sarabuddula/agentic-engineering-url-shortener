package com.agenticengineering.urlshortener.orchestration.approval;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Human approval gates (T060; FR-024, FR-025, FR-046, PVT-005, SC-004; ADR-008). An approval is PENDING for one window
 * (app.approval-timeout-minutes, 5); on timeout it is ESCALATED once with an equal second window; when that elapses it
 * expires and the affected path is safe-stopped by the caller. Only a human with the gate's role and a key identity
 * different from the requester's can decide; self-approval, agent approval and wrong-role attempts are audited and
 * leave the approval pending. Rejections and timeouts are final, never retried; silence is never approval.
 */
@Component
public class ApprovalService {

    public enum Decision { PENDING, APPROVED, REJECTED, ESCALATED }

    /** An APPROVAL row; node is the stage that waits on it (stored as SCOPE "node=Nxx"). */
    public record Approval(long id, String runId, ApprovalKind kind, String gate, String node, String requesterKeyId,
            String approverKeyId, Decision status, Instant timeoutAt, Instant secondWindowTimeoutAt, Instant decidedAt,
            String reason, boolean expired, Instant requestedAt) {

        /** End of the window currently open: the second window once escalated. */
        public Instant expiresAt() {
            return status == Decision.ESCALATED && secondWindowTimeoutAt != null ? secondWindowTimeoutAt : timeoutAt;
        }
    }

    static final String EXPIRED_MARK = "EXPIRED: no decision within two approval windows";

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final Duration window;
    private final TransactionTemplate tx;

    public ApprovalService(JdbcTemplate jdbc, AuditChain audit, TimeSource clock,
            @Value("${app.approval-timeout-minutes:5}") int windowMinutes, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.window = Duration.ofMinutes(windowMinutes);
        this.tx = new TransactionTemplate(transactions);
    }

    public Approval request(String runId, ApprovalKind kind, String node, String requesterKeyId, String reason) {
        return tx.execute(status -> {
            Instant now = clock.now();
            jdbc.update("INSERT INTO APPROVAL (RUN_ID, GATE, REQUESTER_KEY_ID, DECISION, SCOPE, TIMEOUT_AT, REASON, EVIDENCE_CLASS)"
                    + " VALUES (?, ?, ?, 'PENDING', ?, ?, ?, 'HUMAN')", runId, kind.gate(), requesterKeyId, "node=" + node,
                    Timestamp.from(now.plus(window)), reason);
            long id = jdbc.queryForObject("SELECT MAX(ID) FROM APPROVAL WHERE RUN_ID = ?", Long.class, runId);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "APPROVAL_REQUESTED", now, kind.gate(), "PENDING",
                    kind + " for " + node + " requested by " + requesterKeyId + "; approval " + id));
            return find(runId, id);
        });
    }

    /**
     * Records a human decision. Throws SelfApprovalDeniedException (self or agent), ForbiddenException (wrong role),
     * ApprovalExpiredException (both windows elapsed) or IllegalStateException (already decided); none of these changes
     * the approval.
     */
    public Approval decide(String runId, long approvalId, String approverKeyId, KeyIdentity.Role approverRole,
            AuditEntry.ActorType actor, boolean approve, String reason) {
        return decide(runId, approvalId, approverKeyId, approverRole, actor, approve, reason, EvidenceClass.HUMAN);
    }

    /** HUMAN for decisions entered by a person; SIMULATED for decisions executed by a test or scripted fixture (T127). */
    public enum EvidenceClass { HUMAN, SIMULATED }

    public Approval decide(String runId, long approvalId, String approverKeyId, KeyIdentity.Role approverRole,
            AuditEntry.ActorType actor, boolean approve, String reason, EvidenceClass evidenceClass) {
        checkTimeouts();
        Approval approval = find(runId, approvalId);
        Instant now = clock.now();
        if (approval.status() == Decision.APPROVED || approval.status() == Decision.REJECTED) {
            throw new IllegalStateException("approval " + approvalId + " is already " + approval.status());
        }
        if (approval.expired()) {
            throw new ApprovalExpiredException(approvalId);
        }
        if (actor != AuditEntry.ActorType.HUMAN) {
            audit.append(runId, new AuditEntry(actor, "AGENT_APPROVAL_DENIED", now, approval.gate(), "DENIED",
                    "only a human may decide approval " + approvalId + "; attempt by " + approverKeyId));
            throw new SelfApprovalDeniedException("agent approval is not allowed");
        }
        if (approverRole != approval.kind().requiredRole()) {
            audit.append(runId, new AuditEntry(actor, "APPROVAL_ROLE_DENIED", now, approval.gate(), "DENIED",
                    "key " + approverKeyId + " with role " + approverRole + " cannot decide " + approval.kind()));
            throw new ForbiddenException("role " + approval.kind().requiredRole() + " required for " + approval.kind());
        }
        if (approverKeyId.equals(approval.requesterKeyId())) {
            audit.append(runId, new AuditEntry(actor, "SELF_APPROVAL_DENIED", now, approval.gate(), "DENIED",
                    "key " + approverKeyId + " requested approval " + approvalId + " and cannot decide it (FR-046)"));
            throw new SelfApprovalDeniedException("approver key identity must differ from the requester's");
        }
        Decision decision = approve ? Decision.APPROVED : Decision.REJECTED;
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE APPROVAL SET DECISION = ?, APPROVER_KEY_ID = ?, DECIDED_AT = ?, REASON = ?,"
                    + " EVIDENCE_CLASS = ? WHERE ID = ? AND DECISION IN ('PENDING', 'ESCALATED')", decision.name(), approverKeyId,
                    Timestamp.from(now), reason, evidenceClass.name(), approvalId);
            if (updated != 1) {
                throw new IllegalStateException("approval " + approvalId + " changed concurrently");
            }
            audit.append(runId, new AuditEntry(actor, "APPROVAL_DECIDED", now, approval.gate(), decision.name(),
                    approval.kind() + " approval " + approvalId + " by " + approverKeyId + ": " + reason));
        });
        return find(runId, approvalId);
    }

    /**
     * Escalates approvals whose first window elapsed (once, equal second window) and expires those whose second window
     * elapsed. Returns the approvals that expired in this call, each reported exactly once, so the caller can safe-stop
     * the waiting path.
     */
    public List<Approval> checkTimeouts() {
        Instant now = clock.now();
        List<Approval> expired = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT ID, RUN_ID FROM APPROVAL WHERE DECISION IN ('PENDING', 'ESCALATED')")) {
            long id = ((Number) row.get("ID")).longValue();
            String runId = (String) row.get("RUN_ID");
            Approval approval = find(runId, id);
            if (approval.status() == Decision.PENDING && !now.isBefore(approval.timeoutAt())) {
                Instant second = approval.timeoutAt().plus(window);
                String gate = approval.gate();
                tx.executeWithoutResult(status -> {
                    jdbc.update("UPDATE APPROVAL SET DECISION = 'ESCALATED', SECOND_WINDOW_TIMEOUT_AT = ? WHERE ID = ? AND DECISION = 'PENDING'",
                            Timestamp.from(second), id);
                    audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "APPROVAL_ESCALATED", now, gate,
                            "ESCALATED", "first window elapsed; re-issued once until " + second));
                });
                approval = find(runId, id);
            }
            if (approval.status() == Decision.ESCALATED && !approval.expired() && !now.isBefore(approval.secondWindowTimeoutAt())) {
                Approval current = approval;
                tx.executeWithoutResult(status -> {
                    jdbc.update("UPDATE APPROVAL SET REASON = ? WHERE ID = ?", EXPIRED_MARK, id);
                    audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "APPROVAL_EXPIRED", now, current.gate(),
                            "EXPIRED", "second window elapsed without a decision; " + current.node() + " path to be safe-stopped"));
                });
                expired.add(find(runId, id));
            }
        }
        return expired;
    }

    public Approval find(String runId, long approvalId) {
        List<Approval> rows = jdbc.query("SELECT * FROM APPROVAL WHERE ID = ? AND RUN_ID = ?", (rs, i) -> {
            String scope = rs.getString("SCOPE");
            return new Approval(rs.getLong("ID"), rs.getString("RUN_ID"), ApprovalKind.ofGate(rs.getString("GATE")),
                    rs.getString("GATE"), scope != null && scope.startsWith("node=") ? scope.substring(5) : null,
                    rs.getString("REQUESTER_KEY_ID"), rs.getString("APPROVER_KEY_ID"), Decision.valueOf(rs.getString("DECISION")),
                    instant(rs.getObject("TIMEOUT_AT", OffsetDateTime.class)),
                    instant(rs.getObject("SECOND_WINDOW_TIMEOUT_AT", OffsetDateTime.class)),
                    instant(rs.getObject("DECIDED_AT", OffsetDateTime.class)), rs.getString("REASON"),
                    EXPIRED_MARK.equals(rs.getString("REASON")),
                    // the schema keeps no request time; the request opened the first window
                    instant(rs.getObject("TIMEOUT_AT", OffsetDateTime.class)).minus(window));
        }, approvalId, runId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("APPROVAL_NOT_FOUND", "No such approval on this run");
        }
        return rows.get(0);
    }

    /** The most recent approval requested for a node of a run (the one a waiting gate node depends on). */
    public Approval latestForNode(String runId, String node) {
        Long id = jdbc.queryForObject("SELECT MAX(ID) FROM APPROVAL WHERE RUN_ID = ? AND SCOPE = ?", Long.class, runId, "node=" + node);
        if (id == null) {
            throw new IllegalStateException("no approval requested for " + node + " on " + runId);
        }
        return find(runId, id);
    }

    /** Open approvals of a run (PENDING or ESCALATED, not expired), for the run detail. */
    public List<Approval> pending(String runId) {
        List<Approval> open = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT ID FROM APPROVAL WHERE RUN_ID = ? AND DECISION IN ('PENDING', 'ESCALATED') ORDER BY ID",
                Long.class, runId)) {
            Approval approval = find(runId, id);
            if (!approval.expired()) {
                open.add(approval);
            }
        }
        return open;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
