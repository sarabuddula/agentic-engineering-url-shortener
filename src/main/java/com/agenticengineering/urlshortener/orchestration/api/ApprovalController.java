package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.RunDriver;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.api.RequestAttributes;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/orchestrator/runs/{runId}/approvals/{approvalId} (T061; FR-024, FR-046; ADR-008). Endpoint roles
 * APPROVER or RELEASE_OWNER; the approval kind then requires its own role and a key different from the requester's.
 * Approving a CLARIFICATION, POLICY_EXCEPTION or ACCEPTED_LIMITATION requires its payload. After a decision the run
 * resumes (or follows the rejection behaviour) asynchronously.
 */
@RestController
public class ApprovalController {

    private final ApprovalService approvals;
    private final Roles roles;
    private final RunDriver driver;
    private final PolicyExceptionService exceptions;

    public ApprovalController(ApprovalService approvals, Roles roles, RunDriver driver, PolicyExceptionService exceptions) {
        this.approvals = approvals;
        this.roles = roles;
        this.driver = driver;
        this.exceptions = exceptions;
    }

    public record PolicyExceptionPayload(@JsonProperty("policy_check_id") String policyCheckId, @JsonProperty("scope") String scope,
            @JsonProperty("compensating_control") String compensatingControl,
            @JsonProperty("expiry_or_review_condition") String expiryOrReviewCondition) {
    }

    public record ApprovalDecision(@JsonProperty("decision") String decision, @JsonProperty("reason") String reason,
            @JsonProperty("clarification_answer") String clarificationAnswer,
            @JsonProperty("policy_exception") PolicyExceptionPayload policyException,
            @JsonProperty("accepted_limitation_text") String acceptedLimitationText) {
    }

    @PostMapping(path = "/api/v1/orchestrator/runs/{runId}/approvals/{approvalId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> decide(@PathVariable String runId, @PathVariable long approvalId, @RequestBody ApprovalDecision body,
            HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.APPROVER, KeyIdentity.Role.RELEASE_OWNER);
        KeyIdentity.Role role = (KeyIdentity.Role) http.getAttribute(RequestAttributes.KEY_ROLE);
        if (!"APPROVE".equals(body.decision()) && !"REJECT".equals(body.decision())) {
            throw new InvalidRequestException("decision must be APPROVE or REJECT");
        }
        boolean approve = "APPROVE".equals(body.decision());
        ApprovalService.Approval approval = approvals.find(runId, approvalId);
        String reason = body.reason() == null ? "" : body.reason();
        if (approve && approval.kind() == ApprovalKind.POLICY_EXCEPTION) {
            approvePolicyException(runId, approvalId, keyId, role, body.policyException());
            driver.resumeAsync(runId);
            return ResponseEntity.ok().build();
        }
        if (approve) {
            reason = withPayload(approval.kind(), body, reason);
        }
        approvals.decide(runId, approvalId, keyId, role, AuditEntry.ActorType.HUMAN, approve,
                reason.isBlank() ? body.decision().toLowerCase() : reason);
        driver.resumeAsync(runId);
        return ResponseEntity.ok().build(); // the contract's 200 carries no body; the decision is in the run detail and history
    }

    /** G5 approval with the full payload: the exception record gains its authority fields (fifth governed change). */
    private void approvePolicyException(String runId, long approvalId, String keyId, KeyIdentity.Role role, PolicyExceptionPayload p) {
        if (p == null || blank(p.policyCheckId()) || blank(p.scope()) || blank(p.compensatingControl())
                || blank(p.expiryOrReviewCondition())) {
            throw new InvalidRequestException("policy_exception with every field is required to approve a POLICY_EXCEPTION");
        }
        Instant expiry;
        try {
            expiry = Instant.parse(p.expiryOrReviewCondition());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("expiry_or_review_condition must be an ISO-8601 instant");
        }
        PolicyExceptionService.PolicyException exception = exceptions.forApproval(runId, approvalId);
        if (!exception.checkId().equals(p.policyCheckId()) || !exception.scope().equals(p.scope())) {
            throw new InvalidRequestException("policy_check_id and scope must match the requested exception ("
                    + exception.checkId() + ", " + exception.scope() + ")");
        }
        exceptions.approve(runId, exception.id(), keyId, role, p.compensatingControl(), expiry, ApprovalService.EvidenceClass.HUMAN);
    }

    private static String withPayload(ApprovalKind kind, ApprovalDecision body, String reason) {
        return switch (kind) {
            case CLARIFICATION -> {
                if (blank(body.clarificationAnswer())) {
                    throw new InvalidRequestException("clarification_answer is required to approve a CLARIFICATION");
                }
                yield join(reason, "clarification: " + body.clarificationAnswer());
            }
            case ACCEPTED_LIMITATION -> {
                if (blank(body.acceptedLimitationText())) {
                    throw new InvalidRequestException("accepted_limitation_text is required to approve an ACCEPTED_LIMITATION");
                }
                yield join(reason, "accepted limitation: " + body.acceptedLimitationText());
            }
            default -> reason;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String join(String reason, String detail) {
        return reason.isBlank() ? detail : reason + "; " + detail;
    }
}
