package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T074 (engine side; FR-025, FR-026, FR-031): retries and fallbacks run inside persisted runs, every transition is
 * audited, and each failure is recorded for MTTR: FP-1 or FP-2 when recovered, unrecovered when the path safe-stops.
 * The test context uses a 20 ms backoff base instead of the production 1 s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "app.orchestration.retry.base-delay-ms=20")
class ReliabilityEngineTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aTransientStageFailureRecoversByRetryAndIsRecordedAsFp1() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", ctx -> calls.incrementAndGet() < 2 ? StageResult.failed("transient: storage timeout") : StageResult.succeeded());

        String runId = run(executors);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.history(runId)).anyMatch(e -> "N06".equals(e.affected()) && e.result().equals("FAILED->RETRY_WAITING"));
        assertThat(jdbc.queryForMap("SELECT RECOVERED, RECOVERY_MECHANISM FROM FAILURE_EVENT WHERE RUN_ID = ?", runId))
                .containsEntry("RECOVERED", true).containsEntry("RECOVERY_MECHANISM", "FP-1");
    }

    @Test
    void aFallbackRecoveryIsRecordedAsFp2() {
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", StageExecutor.withFallback(ctx -> StageResult.failed("permanent: primary design tool unavailable"),
                ctx -> StageResult.succeeded()));

        String runId = run(executors);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
        assertThat(jdbc.queryForMap("SELECT RECOVERED, RECOVERY_MECHANISM FROM FAILURE_EVENT WHERE RUN_ID = ?", runId))
                .containsEntry("RECOVERED", true).containsEntry("RECOVERY_MECHANISM", "FP-2");
    }

    @Test
    void anUnrecoveredFailureSafeStopsAndStaysUnrecovered() {
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", ctx -> StageResult.failed("permanent: invalid design input"));

        String runId = run(executors);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N06", NodeStatus.SAFE_STOPPED);
        assertThat(jdbc.queryForMap("SELECT RECOVERED, RECOVERY_MECHANISM FROM FAILURE_EVENT WHERE RUN_ID = ?", runId))
                .containsEntry("RECOVERED", false);
    }

    private String run(Map<String, StageExecutor> executors) {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        return runId;
    }

    /** Every stage a labelled fixture that succeeds; no gates, so a run completes unless a stage fails. */
    private Map<String, StageExecutor> fixtures() {
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(Set.of()));
        executors.put("N05", ctx -> {
            if (tasks.statuses(ctx.runId()).isEmpty()) {
                tasks.saveDecomposition(ctx.runId(), List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of())));
            }
            return StageResult.succeeded();
        });
        executors.put("N12", ctx -> StageResult.succeeded(Set.of()));
        executors.remove("N08");
        return executors;
    }
}
