package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.PolicySet;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.orchestration.stages.RequirementIngestion;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control-plane run endpoints (T058; FR-020, FR-045, contracts/openapi.yaml). POST ingests the requirement (N01) and
 * creates the run under the current policy version; GET returns the run detail. Roles follow x-required-roles.
 * Driving the graph past N01 arrives with the approval gates (T059-T061).
 */
@RestController
public class RunController {

    private static final String POLICY_VERSION = PolicySet.v20261018().version();

    private final OrchestrationEngine engine;
    private final WorkflowStore store;
    private final RequirementIngestion ingestion;
    private final Roles roles;

    public RunController(OrchestrationEngine engine, WorkflowStore store, RequirementIngestion ingestion, Roles roles) {
        this.engine = engine;
        this.store = store;
        this.ingestion = ingestion;
        this.roles = roles;
    }

    public record RunRequest(@JsonProperty("requirement_text") String requirementText,
            @JsonProperty("execution_class") String executionClass) {
    }

    @PostMapping(path = "/api/v1/orchestrator/runs", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestBody RunRequest request, HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER);
        String text;
        try {
            text = RequirementIngestion.normalize(request.requirementText());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("requirement_text must not be blank");
        }
        String executionClass = request.executionClass() == null ? "LIVE" : request.executionClass();
        if (!executionClass.equals("LIVE") && !executionClass.equals("REPLAY")) {
            throw new InvalidRequestException("execution_class must be LIVE or REPLAY");
        }
        String runId = engine.createRun("api:" + keyId, POLICY_VERSION, executionClass);
        ingestion.ingest(runId, text);
        return ResponseEntity.status(HttpStatus.CREATED).body(body(store.runDetail(runId).orElseThrow(), false));
    }

    @GetMapping(path = "/api/v1/orchestrator/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> detail(@PathVariable String runId, HttpServletRequest http) {
        roles.require(http, KeyIdentity.Role.ENGINEER, KeyIdentity.Role.APPROVER, KeyIdentity.Role.RELEASE_OWNER,
                KeyIdentity.Role.REVIEWER);
        WorkflowStore.RunDetail detail = store.runDetail(runId)
                .orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        return body(detail, true);
    }

    private static Map<String, Object> body(WorkflowStore.RunDetail detail, boolean full) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", detail.runId());
        body.put("status", detail.status().name());
        body.put("policy_version", detail.policyVersion());
        if (full) {
            body.put("run_revision", detail.runRevision());
            body.put("execution_class", detail.executionClass());
            if (detail.terminalOutcome() != null) {
                body.put("terminal_outcome", detail.terminalOutcome());
            }
            body.put("pending_approvals", List.of()); // approval requests arrive with T059-T061
        }
        return body;
    }
}
