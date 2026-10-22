package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.AcceptedLimitationService;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /runs/{runId}/accepted-limitations (T093; contracts/openapi.yaml): an ENGINEER requests a G6 accepted
 * limitation; the request carries no authority and opens a G6 approval for a RELEASE_OWNER.
 */
@RestController
public class AcceptedLimitationController {

    private final AcceptedLimitationService limitations;
    private final WorkflowStore store;
    private final Roles roles;

    public AcceptedLimitationController(AcceptedLimitationService limitations, WorkflowStore store, Roles roles) {
        this.limitations = limitations;
        this.store = store;
        this.roles = roles;
    }

    public record AcceptedLimitationRequest(@JsonProperty("limitation_id") String limitationId, @JsonProperty("description") String description,
            @JsonProperty("compensating_control") String compensatingControl) {
    }

    @PostMapping(path = "/api/v1/orchestrator/runs/{runId}/accepted-limitations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> request(@PathVariable String runId, @RequestBody AcceptedLimitationRequest body,
            HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        ApprovalService.Approval approval = limitations.request(runId, body.limitationId(), body.description(), body.compensatingControl(), keyId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approval_id", String.valueOf(approval.id()));
        response.put("limitation_id", body.limitationId());
        response.put("status", "PENDING_APPROVAL");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
