package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.EdgeKind;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T054: the N01-N14 stage graph of plan section 3 and task-level execution in N08 from explicit TASK_DEPENDENCY rows
 * (FR-036, NFR-011). Executors here are labelled test fixtures; the real ones arrive in T056, T057, T059-T061, T128.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SdlcWorkflowTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theGraphMatchesPlanSection3() {
        StageGraph graph = SdlcWorkflow.graph();

        assertThat(graph.nodes()).containsExactly("N01", "N02", "N03", "N04", "N05", "N06", "N07", "N08", "N09", "N10", "N11",
                "N12", "N13", "N14");
        assertThat(graph.edges()).contains(
                new StageGraph.Edge("N02", "N03", EdgeKind.CONDITIONAL), new StageGraph.Edge("N02", "N04", EdgeKind.CONDITIONAL),
                new StageGraph.Edge("N08", "N09", EdgeKind.DEPENDENCY), new StageGraph.Edge("N08", "N10", EdgeKind.DEPENDENCY),
                new StageGraph.Edge("N09", "N11", EdgeKind.SYNC), new StageGraph.Edge("N10", "N11", EdgeKind.SYNC),
                new StageGraph.Edge("N12", "N13", EdgeKind.CONDITIONAL), new StageGraph.Edge("N12", "N14", EdgeKind.DEPENDENCY),
                new StageGraph.Edge("N13", "N14", EdgeKind.DEPENDENCY));
    }

    @Test
    void aSeededRunWalksN01ToN14WithParallelTasksFromExplicitDependencyRows() {
        String runId = engine.createRun("fixture:greenfield", "POL-2026-10-18-v1");
        CyclicBarrier parallelTasks = new CyclicBarrier(2);
        Map<String, Instant[]> taskTimes = new ConcurrentHashMap<>();

        OrchestrationEngine.RunOutcome outcome = engine.execute(runId, fixtures(runId, Set.of()), task -> {
            Instant start = Instant.now();
            if (!task.equals("t1")) {
                parallelTasks.await(5, TimeUnit.SECONDS); // t2 and t3 only pass together
            }
            taskTimes.put(task, new Instant[] {start, Instant.now()});
            return StageResult.succeeded();
        });

        // FR-044 (T101): every node succeeds, but the fixture run has no completion evidence, so it stops at the gate
        assertThat(outcome.runStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(outcome.reason()).contains("completion gate");
        Map<String, NodeStatus> nodes = store.nodeStatuses(runId);
        assertThat(nodes).containsEntry("N03", NodeStatus.NOT_APPLICABLE).containsEntry("N04", NodeStatus.NOT_APPLICABLE);
        nodes.forEach((node, status) -> {
            if (!node.equals("N03") && !node.equals("N04")) {
                assertThat(status).as(node).isEqualTo(NodeStatus.SUCCEEDED);
            }
        });
        assertThat(tasks.statuses(runId)).containsOnly(Map.entry("t1", "SUCCEEDED"), Map.entry("t2", "SUCCEEDED"),
                Map.entry("t3", "SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TASK_DEPENDENCY d JOIN TASK t ON t.ID = d.TASK_ID WHERE t.RUN_ID = ?",
                Integer.class, runId)).isEqualTo(2);
        assertThat(taskTimes.get("t2")[0]).isAfterOrEqualTo(taskTimes.get("t1")[1]);
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action)
                .contains("RUN_CREATED", "NODE_TRANSITION", "TASK_TRANSITION", "RUN_TRANSITION");
    }

    @Test
    void aFailedTaskFailsN08AndSafeStopsTheRun() {
        String runId = engine.createRun("fixture:failing-task", "POL-2026-10-18-v1");

        OrchestrationEngine.RunOutcome outcome = engine.execute(runId, fixtures(runId, Set.of()),
                task -> task.equals("t2") ? StageResult.failed("permanent: compile error") : StageResult.succeeded());

        assertThat(outcome.runStatus()).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N08", NodeStatus.SAFE_STOPPED).containsEntry("N09", NodeStatus.PENDING);
        assertThat(tasks.statuses(runId)).containsEntry("t2", "FAILED");
        assertThat(outcome.reason()).contains("N08");
    }

    /** Fixture executors: N02 selects the given branches, N05 decomposes into t1 -> {t2, t3}, N12 selects N13. */
    private Map<String, StageExecutor> fixtures(String runId, Set<String> n02Branches) {
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(n02Branches));
        executors.put("N05", ctx -> {
            tasks.saveDecomposition(ctx.runId(), List.of(
                    new TaskSpec("t1", "add status lookup query", "FR-040", List.of()),
                    new TaskSpec("t2", "add status endpoint", "FR-040", List.of("t1")),
                    new TaskSpec("t3", "document status endpoint", "FR-040", List.of("t1"))));
            return StageResult.succeeded();
        });
        executors.put("N12", ctx -> StageResult.succeeded(Set.of("N13")));
        executors.remove("N08"); // N08 is the engine's own task fan-out
        return executors;
    }
}
