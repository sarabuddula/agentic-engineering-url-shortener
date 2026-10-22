package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
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
 * POST /runs/{runId}/policy-exceptions (fifth governed change, 2026-10-21; FR-033, Constitution VI): an ENGINEER
 * requests an exception for a check; the request carries no authority and opens a G5 approval for a RELEASE_OWNER.
 */
@RestController
public class PolicyExceptionController {

    private final PolicyExceptionService exceptions;
    private final WorkflowStore store;
    private final Roles roles;

    public PolicyExceptionController(PolicyExceptionService exceptions, WorkflowStore store, Roles roles) {
        this.exceptions = exceptions;
        this.store = store;
        this.roles = roles;
    }

    public record PolicyExceptionRequest(@JsonProperty("policy_check_id") String policyCheckId, @JsonProperty("reason") String reason,
            @JsonProperty("scope") String scope) {
    }

    @PostMapping(path = "/api/v1/orchestrator/runs/{runId}/policy-exceptions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> request(@PathVariable String runId, @RequestBody PolicyExceptionRequest body,
            HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        PolicyExceptionService.PolicyException created = exceptions.request(runId, body.policyCheckId(), body.reason(), body.scope(), keyId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exception_id", String.valueOf(created.id()));
        response.put("approval_id", String.valueOf(created.approvalId()));
        response.put("policy_check_id", created.checkId());
        response.put("status", "PENDING_APPROVAL");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
