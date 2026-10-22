package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A run that stops at the N07 design gate after N05 has read the IMPACT_ANALYSIS artifact recorded at N04 (labelled
 * fixture stages; real N07 gate; the artifact read is recorded in NODE_INPUT). {@link #changeUpstream} records a new
 * artifact version: the upstream change event a replan re-validates against. Tests bring it in with @Import.
 */
public class ReplanFixture {

    private final OrchestrationEngine engine;
    private final ArtifactStore artifacts;
    private final TaskStore tasks;
    private final GateExecutor.Factory gates;

    public ReplanFixture(OrchestrationEngine engine, ArtifactStore artifacts, TaskStore tasks, GateExecutor.Factory gates) {
        this.engine = engine;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.gates = gates;
    }

    public String runToDesignGate() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(), task -> StageResult.succeeded());
        return runId;
    }

    public void changeUpstream(String runId) {
        artifacts.record(runId, "IMPACT_ANALYSIS", "{\"impact\":\"revised\"}", "DETERMINISTIC", "N04");
    }

    public void resume(String runId) {
        engine.resume(runId, executors(), task -> StageResult.succeeded());
    }

    public Map<String, StageExecutor> executors() {
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(Set.of("N04")));
        executors.put("N04", ctx -> {
            if (artifacts.latest(ctx.runId(), "IMPACT_ANALYSIS").isEmpty()) {
                artifacts.record(ctx.runId(), "IMPACT_ANALYSIS", "{\"impact\":\"initial\"}", "DETERMINISTIC", "N04");
            }
            return StageResult.succeeded();
        });
        executors.put("N05", ctx -> {
            artifacts.read(ctx.runId(), "IMPACT_ANALYSIS", "N05");
            if (tasks.statuses(ctx.runId()).isEmpty()) {
                tasks.saveDecomposition(ctx.runId(), List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of())));
            }
            return StageResult.succeeded();
        });
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.remove("N08");
        return executors;
    }
}
