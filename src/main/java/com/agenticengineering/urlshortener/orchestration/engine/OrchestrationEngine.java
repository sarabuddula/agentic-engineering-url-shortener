package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.port.IdGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Runs the SDLC graph for a workflow run (T054; FR-020, FR-036, NFR-011). Stage executors are supplied per run; N08 is
 * the engine's own task fan-out over the run's TASK_DEPENDENCY graph, on a separate pool so nested parallelism cannot
 * starve the stage pool. Every node, task and run transition is persisted and audited through the stores. A run whose
 * graph does not finish safe-stops (FP-4): the failed nodes move FAILED -> SAFE_STOPPED with the reason recorded.
 */
@Component
public class OrchestrationEngine {

    /** Final run state and why. */
    public record RunOutcome(RunStatus runStatus, String reason) {
    }

    private final WorkflowStore store;
    private final TaskStore tasks;
    private final ExecutorService stagePool;
    private final ExecutorService taskPool;
    private final IdGenerator ids;

    public OrchestrationEngine(WorkflowStore store, TaskStore tasks, @Qualifier("orchestrationStagePool") ExecutorService stagePool,
            @Qualifier("orchestrationTaskPool") ExecutorService taskPool, IdGenerator ids) {
        this.store = store;
        this.tasks = tasks;
        this.stagePool = stagePool;
        this.taskPool = taskPool;
        this.ids = ids;
    }

    public String createRun(String requirementRef, String policyVersion) {
        return createRun(requirementRef, policyVersion, "LIVE");
    }

    public String createRun(String requirementRef, String policyVersion, String executionClass) {
        String runId = ids.newId();
        store.createRun(runId, requirementRef, policyVersion, SdlcWorkflow.graph(), executionClass);
        return runId;
    }

    public RunOutcome execute(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
        Map<String, StageTask> stageTasks = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            if (node.equals(SdlcWorkflow.IMPLEMENTATION_NODE)) {
                stageTasks.put(node, ctx -> runTasks(runId, taskExecutor));
            } else {
                StageExecutor executor = executors.get(node);
                if (executor == null) {
                    throw new IllegalArgumentException("no executor registered for " + node);
                }
                stageTasks.put(node, ctx -> executor.execute(new RunContext(runId, node)));
            }
        }
        GraphScheduler.RunResult result = new GraphScheduler(stagePool,
                (node, from, to, reason) -> store.transitionNode(runId, node, from, to, reason)).execute(SdlcWorkflow.graph(), stageTasks);
        if (result.succeeded()) {
            store.transitionRun(runId, RunStatus.RUNNING, RunStatus.COMPLETED, "all stages succeeded or not applicable");
            return new RunOutcome(RunStatus.COMPLETED, "all stages succeeded or not applicable");
        }
        StringBuilder reason = new StringBuilder();
        for (String failed : result.failedNodes()) {
            String cause = result.reasons().getOrDefault(failed, "failed");
            store.transitionNode(runId, failed, NodeStatus.FAILED, NodeStatus.SAFE_STOPPED,
                    "FP-4 safe-stop: permanent failure on a mandatory path (" + cause + ")");
            reason.append(reason.isEmpty() ? "" : "; ").append(failed).append(": ").append(cause);
        }
        store.transitionRun(runId, RunStatus.RUNNING, RunStatus.SAFE_STOPPED, "safe-stop after " + reason);
        return new RunOutcome(RunStatus.SAFE_STOPPED, reason.toString());
    }

    /** N08: run the decomposed tasks as a nested graph; N08 succeeds only if every task succeeded. */
    private StageResult runTasks(String runId, TaskExecutor taskExecutor) {
        StageGraph taskGraph = tasks.taskGraph(runId);
        if (taskGraph.nodes().isEmpty()) {
            return StageResult.failed("no decomposed tasks to implement");
        }
        Map<String, StageTask> work = new HashMap<>();
        taskGraph.nodes().forEach(key -> work.put(key, ctx -> taskExecutor.execute(key)));
        GraphScheduler.RunResult result = new GraphScheduler(taskPool,
                (key, from, to, reason) -> tasks.transitionTask(runId, key, from.name(), to.name(), reason)).execute(taskGraph, work);
        return result.succeeded() ? StageResult.succeeded()
                : StageResult.failed("tasks failed: " + String.join(", ", result.failedNodes()));
    }
}
