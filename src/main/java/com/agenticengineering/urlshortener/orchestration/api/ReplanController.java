package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanningService;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /runs/{runId}/replan (T072; FR-029): ENGINEER stages a replan; 202 without a body, as the contract says; the
 * acceptance approval appears in the run detail's pending approvals. 409 REPLAN_CONFLICT when nothing can be replanned.
 */
@RestController
public class ReplanController {

    private final ReplanningService replanning;
    private final WorkflowStore store;
    private final Roles roles;

    public ReplanController(ReplanningService replanning, WorkflowStore store, Roles roles) {
        this.replanning = replanning;
        this.store = store;
        this.roles = roles;
    }

    @PostMapping("/api/v1/orchestrator/runs/{runId}/replan")
    public ResponseEntity<Void> replan(@PathVariable String runId, HttpServletRequest http) {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        replanning.request(runId, keyId);
        return ResponseEntity.accepted().build();
    }
}
