package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunDriver;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.RequestAttributes;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /runs/{runId}/resume (T110; FR-028): ENGINEER or APPROVER; the resumption is authorized and recorded on the
 * request (governed stops need an APPROVER other than the requester), then the run continues off the request thread.
 * 200 without a body and 409 without a body, as the contract defines them.
 */
@RestController
public class ResumeController {

    private final OrchestrationEngine engine;
    private final RunDriver driver;
    private final WorkflowStore store;
    private final Roles roles;

    public ResumeController(OrchestrationEngine engine, RunDriver driver, WorkflowStore store, Roles roles) {
        this.engine = engine;
        this.driver = driver;
        this.store = store;
        this.roles = roles;
    }

    @PostMapping("/api/v1/orchestrator/runs/{runId}/resume")
    public ResponseEntity<Void> resume(@PathVariable String runId, HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER, KeyIdentity.Role.APPROVER);
        KeyIdentity.Role role = (KeyIdentity.Role) http.getAttribute(RequestAttributes.KEY_ROLE);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        engine.authorizeResume(runId, keyId, role);
        driver.continueAsync(runId);
        return ResponseEntity.ok().build();
    }
}
