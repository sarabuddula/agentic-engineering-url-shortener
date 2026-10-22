package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
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
    private final ApprovalService approvals;
    private final ReleaseDecisionStore releases;

    public OrchestrationEngine(WorkflowStore store, TaskStore tasks, @Qualifier("orchestrationStagePool") ExecutorService stagePool,
            @Qualifier("orchestrationTaskPool") ExecutorService taskPool, IdGenerator ids, ApprovalService approvals,
            ReleaseDecisionStore releases) {
        this.store = store;
        this.tasks = tasks;
        this.stagePool = stagePool;
        this.taskPool = taskPool;
        this.ids = ids;
        this.approvals = approvals;
        this.releases = releases;
    }

    public String createRun(String requirementRef, String policyVersion) {
        return createRun(requirementRef, policyVersion, "LIVE");
    }

    public String createRun(String requirementRef, String policyVersion, String executionClass) {
        String runId = ids.newId();
        store.createRun(runId, requirementRef, policyVersion, SdlcWorkflow.graph(), executionClass);
        return runId;
    }

    /** Runs the graph from the run's persisted state until it completes, waits at a gate, or stops. */
    public RunOutcome execute(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
        return drive(runId, executors, taskExecutor);
    }

    /**
     * Resumes a run that waits at a gate: decided gates are settled first (approved: node succeeds; rejected: governed
     * termination; still pending: GATE_BLOCKED audit and nothing moves), then the graph continues.
     */
    public RunOutcome resume(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
        RunStatus runStatus = store.runStatus(runId);
        if (runStatus.isTerminal() || runStatus == RunStatus.SAFE_STOPPED) {
            return new RunOutcome(runStatus, "run is " + runStatus);
        }
        boolean progressed = false;
        for (Map.Entry<String, NodeStatus> node : store.nodeStatuses(runId).entrySet()) {
            if (node.getValue() != NodeStatus.AWAITING_APPROVAL) {
                continue;
            }
            ApprovalService.Approval approval = approvals.latestForNode(runId, node.getKey());
            if (approval.status() == ApprovalService.Decision.APPROVED) {
                store.transitionNode(runId, node.getKey(), NodeStatus.AWAITING_APPROVAL, NodeStatus.RUNNING,
                        "approval " + approval.id() + " approved by " + approval.approverKeyId());
                store.transitionNode(runId, node.getKey(), NodeStatus.RUNNING, NodeStatus.SUCCEEDED,
                        approval.gate() + " passed with recorded approval " + approval.id());
                progressed = true;
            } else if (approval.status() == ApprovalService.Decision.REJECTED) {
                String reason = "approval " + approval.id() + " rejected at " + node.getKey() + " by " + approval.approverKeyId()
                        + ": " + approval.reason();
                store.transitionNode(runId, node.getKey(), NodeStatus.AWAITING_APPROVAL, NodeStatus.FAILED, reason);
                store.transitionRun(runId, runStatus, RunStatus.TERMINATED,
                        "governed termination after " + reason + " (rework cycles deferred, L-SC-04)");
                return new RunOutcome(RunStatus.TERMINATED, reason);
            } else {
                store.auditGateBlocked(runId, node.getKey(), "approval " + approval.id() + " (" + approval.gate()
                        + ") undecided; progression blocked");
            }
        }
        if (!progressed) {
            return new RunOutcome(runStatus, "waiting for a human decision");
        }
        store.transitionRun(runId, runStatus, RunStatus.RUNNING, "resumed after a recorded human decision");
        return drive(runId, executors, taskExecutor);
    }

    /** Expires approvals whose escalation window elapsed and safe-stops the waiting node and the run (PVT-005). */
    public void handleTimeouts() {
        for (ApprovalService.Approval expired : approvals.checkTimeouts()) {
            String runId = expired.runId();
            if (store.nodeStatuses(runId).get(expired.node()) == NodeStatus.AWAITING_APPROVAL) {
                store.transitionNode(runId, expired.node(), NodeStatus.AWAITING_APPROVAL, NodeStatus.SAFE_STOPPED,
                        "approval " + expired.id() + " expired after escalation (FP-4)");
            }
            RunStatus runStatus = store.runStatus(runId);
            if (runStatus == RunStatus.AWAITING_APPROVAL || runStatus == RunStatus.SUSPENDED_CLARIFICATION
                    || runStatus == RunStatus.RUNNING) {
                store.transitionRun(runId, runStatus, RunStatus.SAFE_STOPPED, "approval " + expired.id() + " (" + expired.gate()
                        + ") expired without a decision; silence is never approval");
            }
        }
    }

    private RunOutcome drive(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
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
                (node, from, to, reason) -> store.transitionNode(runId, node, from, to, reason))
                .execute(SdlcWorkflow.graph(), stageTasks, store.nodeStatuses(runId));
        if (result.succeeded()) {
            if (releases.recommendation(runId).filter("NOT_READY"::equals).isPresent()) {
                store.finishRun(runId, RunStatus.RUNNING, RunStatus.TERMINATED, "NOT_READY",
                        "graph finished with a NOT READY determination; release authorization was blocked (FR-033)");
                return new RunOutcome(RunStatus.TERMINATED, "NOT_READY");
            }
            store.transitionRun(runId, RunStatus.RUNNING, RunStatus.COMPLETED, "all stages succeeded or not applicable");
            return new RunOutcome(RunStatus.COMPLETED, "all stages succeeded or not applicable");
        }
        if (!result.failedNodes().isEmpty()) {
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
        RunStatus waiting = result.awaitingNodes().contains("N03") ? RunStatus.SUSPENDED_CLARIFICATION : RunStatus.AWAITING_APPROVAL;
        store.transitionRun(runId, RunStatus.RUNNING, waiting, "waiting for a human decision at " + result.awaitingNodes());
        return new RunOutcome(waiting, "waiting at " + result.awaitingNodes());
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
