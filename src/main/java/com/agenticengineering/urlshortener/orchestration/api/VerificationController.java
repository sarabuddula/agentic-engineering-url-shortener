package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /runs/{runId}/verify (T064; FR-048, ADR-005): re-verifies the run's audit chain on request, REVIEWER only.
 * Detects tampering for the stated checks; it does not prevent it (A-011).
 */
@RestController
public class VerificationController {

    private final AuditChain chain;
    private final WorkflowStore store;
    private final Roles roles;

    public VerificationController(AuditChain chain, WorkflowStore store, Roles roles) {
        this.chain = chain;
        this.store = store;
        this.roles = roles;
    }

    @GetMapping(path = "/api/v1/orchestrator/runs/{runId}/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> verify(@PathVariable String runId, HttpServletRequest http) {
        roles.require(http, KeyIdentity.Role.REVIEWER);
        WorkflowStore.RunDetail run = store.runDetail(runId)
                .orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        ChainVerification result = chain.verify(runId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", run.runId());
        body.put("verified", result.intact());
        if (result.intact()) {
            body.put("head_hash", chain.headHash(runId));
            body.put("event_count", result.eventCount());
        } else {
            body.put("failed_at_seq", result.failedPosition());
            body.put("check", contractName(result.check()));
        }
        return body;
    }

    /** The contract's check names; HEAD_MISMATCH (chain disagrees with the retained head) has no contract name yet. */
    static String contractName(ChainVerification.Check check) {
        return switch (check) {
            case HASH_MISMATCH -> "hash-mismatch";
            case PREDECESSOR_MISMATCH -> "link-broken";
            case SEQUENCE_GAP -> "sequence-gap";
            case TRUNCATION -> "truncation";
            case HEAD_MISMATCH -> "head-mismatch";
        };
    }
}
