package com.agenticengineering.urlshortener.orchestration.policy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T063 policy exceptions (FR-033, Constitution VI). A request records the exception without authority and opens a G5
 * approval referenced by POLICY_EXCEPTION.APPROVAL_ID (fifth governed change, 2026-10-21).
 * Only the approval path, through ApprovalService's role and self-approval rules, fills in the approving authority,
 * compensating control, approved_at and expiry. Validity (approved and unexpired) is judged at N12.
 */
@Component
public class PolicyExceptionService {

    public record PolicyException(long id, String runId, String checkId, String reason, String scope, String approvingAuthorityKeyId,
            String compensatingControl, Instant approvedAt, Instant expiresAt, long approvalId) {

        public boolean approved() {
            return approvedAt != null;
        }

        public boolean validAt(Instant now) {
            return approved() && expiresAt != null && expiresAt.isAfter(now);
        }
    }

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;
    private final DecisionLineage lineage;

    public PolicyExceptionService(JdbcTemplate jdbc, ApprovalService approvals, AuditChain audit, TimeSource clock,
            PlatformTransactionManager transactions, DecisionLineage lineage) {
        this.lineage = lineage;
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public PolicyException request(String runId, String checkId, String reason, String scope, String requesterKeyId) {
        if (blank(checkId) || blank(reason) || blank(scope)) {
            throw new InvalidRequestException("check, reason and scope are required for a policy exception");
        }
        try {
            PolicySet.v20261018().check(checkId);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("policy_check_id " + checkId + " is not a check of POL-2026-10-18-v1");
        }
        return tx.execute(status -> {
            jdbc.update("INSERT INTO POLICY_EXCEPTION (RUN_ID, CHECK_ID, REASON, SCOPE) VALUES (?, ?, ?, ?)", runId, checkId, reason, scope);
            long id = jdbc.queryForObject("SELECT MAX(ID) FROM POLICY_EXCEPTION WHERE RUN_ID = ?", Long.class, runId);
            ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.POLICY_EXCEPTION, "N12", requesterKeyId,
                    "policy exception " + id + " for " + checkId + ": " + reason);
            jdbc.update("UPDATE POLICY_EXCEPTION SET APPROVAL_ID = ? WHERE ID = ?", approval.id(), id);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "POLICY_EXCEPTION_REQUESTED", clock.now(), "G5", "PENDING",
                    "exception " + id + " for " + checkId + " requested by " + requesterKeyId + "; scope " + scope));
            return find(runId, id);
        });
    }

    /** Approves through the G5 approval (RELEASE_OWNER, distinct from the requester) and records the authority fields. */
    public PolicyException approve(String runId, long exceptionId, String approverKeyId, KeyIdentity.Role approverRole,
            String compensatingControl, Instant expiresAt, ApprovalService.EvidenceClass evidenceClass) {
        PolicyException exception = find(runId, exceptionId);
        Instant now = clock.now();
        if (blank(compensatingControl)) {
            throw new InvalidRequestException("a compensating control is required to approve a policy exception");
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new InvalidRequestException("a policy exception needs an expiry in the future");
        }
        if (exception.approved()) {
            throw new IllegalStateException("policy exception " + exceptionId + " is already approved");
        }
        // decided outside the transaction below so a denial's audit event is kept when the denial is thrown
        ApprovalService.Approval decided = approvals.decide(runId, exception.approvalId(), approverKeyId, approverRole,
                AuditEntry.ActorType.HUMAN, true, "exception " + exceptionId + " for " + exception.checkId()
                        + "; compensating control: " + compensatingControl + "; expires " + expiresAt, evidenceClass);
        return tx.execute(status -> {
            jdbc.update("UPDATE POLICY_EXCEPTION SET APPROVING_AUTHORITY_KEY_ID = ?, COMPENSATING_CONTROL = ?, APPROVED_AT = ?,"
                    + " EXPIRES_AT = ? WHERE ID = ? AND APPROVED_AT IS NULL", approverKeyId, compensatingControl,
                    Timestamp.from(decided.decidedAt()), Timestamp.from(expiresAt), exceptionId);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "POLICY_EXCEPTION_APPROVED", now, "G5", "APPROVED",
                    "exception " + exceptionId + " for " + exception.checkId() + " approved by " + approverKeyId + " until " + expiresAt));
            lineage.record(runId, "POLICY_EXCEPTION", AuditEntry.ActorType.HUMAN, "POLICY_EXCEPTION " + exceptionId + " for "
                    + exception.checkId() + " APPROVED by " + approverKeyId + " until " + expiresAt, "reason: " + exception.reason()
                    + "; scope: " + exception.scope() + "; compensating control: " + compensatingControl,
                    List.of("leave " + exception.checkId() + " failing, so the run is NOT READY"), lineage.currentArtifacts(runId));
            return find(runId, exceptionId);
        });
    }

    /** The exception a G5 approval decides, for approvals entered through the approvals endpoint. */
    public PolicyException forApproval(String runId, long approvalId) {
        List<PolicyException> rows = jdbc.query("SELECT * FROM POLICY_EXCEPTION WHERE RUN_ID = ? AND APPROVAL_ID = ?", this::map, runId,
                approvalId);
        if (rows.isEmpty()) {
            throw new InvalidRequestException("approval " + approvalId + " does not decide a recorded policy exception");
        }
        return rows.get(0);
    }

    public PolicyException find(String runId, long exceptionId) {
        List<PolicyException> rows = jdbc.query("SELECT * FROM POLICY_EXCEPTION WHERE RUN_ID = ? AND ID = ?", this::map, runId, exceptionId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("POLICY_EXCEPTION_NOT_FOUND", "no policy exception " + exceptionId + " in run " + runId);
        }
        return rows.get(0);
    }

    /** The latest exception per check for the run, approved or not. */
    public Map<String, PolicyException> latestPerCheck(String runId) {
        return jdbc.query("SELECT * FROM POLICY_EXCEPTION WHERE RUN_ID = ? ORDER BY ID", this::map, runId).stream()
                .collect(Collectors.toMap(PolicyException::checkId, e -> e, (older, newer) -> newer));
    }

    private PolicyException map(ResultSet rs, int row) throws SQLException {
        long id = rs.getLong("ID");
        return new PolicyException(id, rs.getString("RUN_ID"), rs.getString("CHECK_ID"), rs.getString("REASON"), rs.getString("SCOPE"),
                rs.getString("APPROVING_AUTHORITY_KEY_ID"), rs.getString("COMPENSATING_CONTROL"), instant(rs.getTimestamp("APPROVED_AT")),
                instant(rs.getTimestamp("EXPIRES_AT")), rs.getLong("APPROVAL_ID"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
