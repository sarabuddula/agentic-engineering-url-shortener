package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.persistence.PolicyStore;
import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /runs/{runId}/release-readiness (T081; FR-037): the N12 recommendation, the latest outcome of every policy check,
 * blocking reasons, individually approved limitations and the human decision. 404 RELEASE_NOT_DETERMINED until N12
 * has run. The decision itself is recorded through the approvals endpoint (G7, RELEASE_OWNER).
 */
@RestController
public class ReleaseReadinessController {

    private final ReleaseDecisionStore releases;
    private final PolicyStore policies;
    private final WorkflowStore store;
    private final JdbcTemplate jdbc;
    private final Roles roles;

    public ReleaseReadinessController(ReleaseDecisionStore releases, PolicyStore policies, WorkflowStore store, JdbcTemplate jdbc,
            Roles roles) {
        this.releases = releases;
        this.policies = policies;
        this.store = store;
        this.jdbc = jdbc;
        this.roles = roles;
    }

    @GetMapping(path = "/api/v1/orchestrator/runs/{runId}/release-readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> readiness(@PathVariable String runId, HttpServletRequest http) {
        roles.require(http, KeyIdentity.Role.RELEASE_OWNER, KeyIdentity.Role.REVIEWER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        ReleaseDecisionStore.ReleaseDecision decision = releases.find(runId).orElseThrow(
                () -> new ResourceNotFoundException("RELEASE_NOT_DETERMINED", "Release readiness is determined at N12, which has not run"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run_id", runId);
        body.put("recommendation", decision.recommendation());
        body.put("policy_outcomes", policies.latestOutcomes(runId).stream()
                .map(e -> Map.of("check_id", e.getKey(), "outcome", e.getValue())).toList());
        List<String> limitations = jdbc.queryForList("SELECT DESCRIPTION FROM ACCEPTED_LIMITATION WHERE RUN_ID = ? ORDER BY ID",
                String.class, runId);
        if (!limitations.isEmpty()) {
            body.put("accepted_limitations", limitations);
        }
        if (!decision.blockingReasons().isEmpty()) {
            body.put("blocking_reasons", decision.blockingReasons());
        }
        body.put("decision", decision.decision());
        return body;
    }
}
