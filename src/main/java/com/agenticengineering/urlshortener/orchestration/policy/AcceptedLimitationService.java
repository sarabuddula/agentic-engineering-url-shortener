package com.agenticengineering.urlshortener.orchestration.policy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * G6 accepted limitations (T093; FR-037, CL-5; human decision 2026-10-22, approvals/2026-10-22-t093-g6-requests.md). A
 * request records the limitation without authority (LIMITATION_REQUEST) and opens a G6 approval; only its approval by a
 * RELEASE_OWNER other than the requester writes the ACCEPTED_LIMITATION row with the approval reference. N12 recommends
 * READY_WITH_ACCEPTED_LIMITATIONS only when no mandatory check blocks, so a limitation never waives a mandatory FAIL.
 */
@Component
public class AcceptedLimitationService {

    /** The run can no longer take a limitation (it is terminal). */
    public static final class LimitationConflictException extends RuntimeException {
        public LimitationConflictException(String message) {
            super(message);
        }
    }

    private static final Pattern LIMITATION_ID = Pattern.compile("L-[A-Z]{1,8}-\\d{1,3}");

    private final JdbcTemplate jdbc;
    private final ApprovalService approvals;
    private final WorkflowStore store;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;
    private final DecisionLineage lineage;

    public AcceptedLimitationService(JdbcTemplate jdbc, ApprovalService approvals, WorkflowStore store, AuditChain audit, TimeSource clock,
            PlatformTransactionManager transactions, DecisionLineage lineage) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.lineage = lineage;
    }

    /** Records the request and opens the G6 approval; returns the approval. */
    public ApprovalService.Approval request(String runId, String limitationId, String description, String compensatingControl,
            String requesterKeyId) {
        if (blank(limitationId) || blank(description) || blank(compensatingControl)) {
            throw new InvalidRequestException("limitation_id, description and compensating_control are required");
        }
        if (!LIMITATION_ID.matcher(limitationId).matches()) {
            throw new InvalidRequestException("limitation_id must look like L-SC-01");
        }
        if (store.runStatus(runId).isTerminal()) {
            throw new LimitationConflictException("run " + runId + " is " + store.runStatus(runId) + "; a limitation needs an open run");
        }
        return tx.execute(status -> {
            jdbc.update("INSERT INTO LIMITATION_REQUEST (RUN_ID, LIMITATION_REF, DESCRIPTION, COMPENSATING_CONTROL, REQUESTED_BY, REQUESTED_AT)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", runId, limitationId, description, compensatingControl, requesterKeyId,
                    Timestamp.from(clock.now()));
            long id = jdbc.queryForObject("SELECT MAX(ID) FROM LIMITATION_REQUEST WHERE RUN_ID = ?", Long.class, runId);
            ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.ACCEPTED_LIMITATION, "N12", requesterKeyId,
                    "accepted limitation " + limitationId + ": " + description);
            jdbc.update("UPDATE LIMITATION_REQUEST SET APPROVAL_ID = ? WHERE ID = ?", approval.id(), id);
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "ACCEPTED_LIMITATION_REQUESTED", clock.now(), "G6", "PENDING",
                    "limitation " + limitationId + " requested by " + requesterKeyId + "; approval " + approval.id()));
            return approval;
        });
    }

    /** After an approved G6 decision: writes the ACCEPTED_LIMITATION row; a G6 without a recorded request writes nothing. */
    public void recordApproved(String runId, long approvalId, String approverKeyId, String acceptedText) {
        List<Map<String, Object>> requests = jdbc.queryForList("SELECT LIMITATION_REF, DESCRIPTION, COMPENSATING_CONTROL FROM LIMITATION_REQUEST"
                + " WHERE RUN_ID = ? AND APPROVAL_ID = ?", runId, approvalId);
        if (requests.isEmpty()) {
            return;
        }
        String ref = (String) requests.get(0).get("LIMITATION_REF");
        String description = ref + ": " + requests.get(0).get("DESCRIPTION") + " (accepted as: " + acceptedText + ")";
        String control = (String) requests.get(0).get("COMPENSATING_CONTROL");
        ApprovalService.Approval approval = approvals.find(runId, approvalId);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ACCEPTED_LIMITATION (RUN_ID, DESCRIPTION, APPROVAL_ID, COMPENSATING_CONTROL, APPROVED_AT) VALUES (?, ?, ?, ?, ?)",
                    runId, description, approvalId, control, Timestamp.from(approval.decidedAt()));
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "ACCEPTED_LIMITATION_APPROVED", clock.now(), "G6", "APPROVED",
                    "limitation " + ref + " accepted by " + approverKeyId + " (approval " + approvalId + ")"));
            lineage.record(runId, "ACCEPTED_LIMITATION", AuditEntry.ActorType.HUMAN, "ACCEPTED_LIMITATION " + ref + " APPROVED by "
                    + approverKeyId, description + "; compensating control: " + control,
                    List.of("leave " + ref + " unaccepted, so the release cannot be READY WITH ACCEPTED LIMITATIONS"), lineage.currentArtifacts(runId));
        });
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
