package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /runs/{runId}/history (T085; FR-023, FR-030, PVT-007): the run's audit chain in sequence order - transitions,
 * approvals, retries, failures, replanning, decisions, terminal outcome and the final verification. Entries that record
 * a decision (DECISION_RECORDED, affected "decision:{id}") carry its rationale, rejected alternatives and artifact refs
 * from DECISION_LINEAGE.
 */
@RestController
public class RunHistoryController {

    private final WorkflowStore store;
    private final DecisionLineage lineage;
    private final Roles roles;

    public RunHistoryController(WorkflowStore store, DecisionLineage lineage, Roles roles) {
        this.store = store;
        this.lineage = lineage;
        this.roles = roles;
    }

    @GetMapping(path = "/api/v1/orchestrator/runs/{runId}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> history(@PathVariable String runId, HttpServletRequest http) {
        roles.require(http, KeyIdentity.Role.ENGINEER, KeyIdentity.Role.APPROVER, KeyIdentity.Role.RELEASE_OWNER, KeyIdentity.Role.REVIEWER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        Map<Long, DecisionLineage.Decision> decisions = new LinkedHashMap<>();
        lineage.forRun(runId).forEach(d -> decisions.put(d.id(), d));
        return store.history(runId).stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("seq", e.seq());
            entry.put("actor_type", e.actorType());
            entry.put("action", e.action());
            entry.put("affected", e.affected() == null ? "" : e.affected());
            decision(e, decisions).ifPresent(d -> {
                entry.put("rationale", d.rationale() == null ? "" : d.rationale());
                entry.put("rejected_alternatives", d.rejectedAlternatives());
                entry.put("artifact_refs", d.artifactRefs());
            });
            entry.put("result", e.result());
            entry.put("reason", e.reason() == null ? "" : e.reason());
            entry.put("occurred_at", e.occurredAt().toString());
            return entry;
        }).toList();
    }

    private static Optional<DecisionLineage.Decision> decision(WorkflowStore.HistoryEntry e, Map<Long, DecisionLineage.Decision> decisions) {
        if (!"DECISION_RECORDED".equals(e.action()) || e.affected() == null || !e.affected().startsWith("decision:")) {
            return Optional.empty();
        }
        return Optional.ofNullable(decisions.get(Long.parseLong(e.affected().substring("decision:".length()))));
    }
}
