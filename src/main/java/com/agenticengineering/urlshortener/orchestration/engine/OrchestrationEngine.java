package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureRecorder;
import com.agenticengineering.urlshortener.orchestration.reliability.RecoveryMechanism;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanningService;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.port.IdGenerator;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final AuditChain chain;
    private final TimeSource clock;
    private final ReplanningService replanning;
    private final FailureRecorder failureRecorder;
    private final CompletionGate completionGate;
    private final DecisionLineage lineage;
    private final RetryPolicy retryPolicy;

    public OrchestrationEngine(WorkflowStore store, TaskStore tasks, @Qualifier("orchestrationStagePool") ExecutorService stagePool,
            @Qualifier("orchestrationTaskPool") ExecutorService taskPool, IdGenerator ids, ApprovalService approvals,
            ReleaseDecisionStore releases, AuditChain chain, TimeSource clock, ReplanningService replanning,
            FailureRecorder failureRecorder, @Value("${app.orchestration.retry.base-delay-ms:1000}") long retryBaseDelayMs,
            CompletionGate completionGate, DecisionLineage lineage) {
        this.store = store;
        this.tasks = tasks;
        this.stagePool = stagePool;
        this.taskPool = taskPool;
        this.ids = ids;
        this.approvals = approvals;
        this.releases = releases;
        this.chain = chain;
        this.clock = clock;
        this.replanning = replanning;
        this.failureRecorder = failureRecorder;
        this.completionGate = completionGate;
        this.lineage = lineage;
        this.retryPolicy = new RetryPolicy(3, Duration.ofMillis(retryBaseDelayMs), Duration.ofSeconds(60));
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
        return verifiedIfTerminal(runId, drive(runId, executors, taskExecutor));
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
        if (replanning.settle(runId)) {
            // an accepted replan activated the next revision: drive it from the carried-over state
            store.transitionRun(runId, runStatus, RunStatus.RUNNING, "resumed under the revision activated by an accepted replan");
            return verifiedIfTerminal(runId, drive(runId, executors, taskExecutor));
        }
        return verifiedIfTerminal(runId, settleAndContinue(runId, runStatus, executors, taskExecutor));
    }

    /**
     * Governed resumption of a safe-stopped run (T110; FR-028; human decision 2026-10-19), then the run continues.
     */
    public RunOutcome resumeSafeStopped(String runId, String keyId, KeyIdentity.Role role, Map<String, StageExecutor> executors,
            TaskExecutor taskExecutor) {
        authorizeResume(runId, keyId, role);
        return continueRun(runId, executors, taskExecutor);
    }

    /**
     * Checks and records the resumption: only SAFE_STOPPED runs; governed stops (approval timeout, boot recovery) need an
     * APPROVER other than the run's requester; a completion-gate stop needs the missing evidence; unverifiable pending
     * operations block it. Stopped nodes go back to READY and the run to RUNNING.
     */
    public void authorizeResume(String runId, String keyId, KeyIdentity.Role role) {
        RunStatus status = store.runStatus(runId);
        if (status != RunStatus.SAFE_STOPPED) {
            throw new ResumeConflictException("run is " + status + ", not SAFE_STOPPED");
        }
        String cause = store.safeStopCause(runId);
        boolean governed = "BOOT_RECOVERY".equals(cause) || "APPROVAL_TIMEOUT".equals(cause);
        if (governed && role != KeyIdentity.Role.APPROVER) {
            chain.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "RESUME_DENIED", clock.now(), runId, "DENIED",
                    "key " + keyId + " (" + role + ") cannot resume a governed stop (" + cause + "); an APPROVER is required"));
            throw new ForbiddenException("an APPROVER key is required to resume a governed stop (" + cause + ")");
        }
        if (governed && keyId.equals(store.requesterKeyId(runId))) {
            chain.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "RESUME_DENIED", clock.now(), runId, "DENIED",
                    "key " + keyId + " requested the run and cannot authorize resuming its governed stop (" + cause + ")"));
            throw new SelfApprovalDeniedException("the resuming APPROVER must differ from the run's requester");
        }
        if ("COMPLETION_GATE".equals(cause)) {
            List<CompletionGate.Condition> unmet = completionGate.unmet(runId);
            if (!unmet.isEmpty()) {
                throw new ResumeConflictException("completion evidence still missing: " + unmet);
            }
        }
        if (store.unverifiablePendingOperations(runId) > 0) {
            throw new ResumeConflictException("pending operations without an effect-verification rule; a human must resolve them");
        }
        chain.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "RUN_RESUMED", clock.now(), runId, "RESUMED",
                "safe-stop cause " + cause + " addressed; resumed by " + keyId + " (" + role + ")"));
        store.nodeStatuses(runId).forEach((node, nodeStatus) -> {
            if (nodeStatus == NodeStatus.SAFE_STOPPED) {
                store.transitionNode(runId, node, NodeStatus.SAFE_STOPPED, NodeStatus.READY, "resumption re-checks entry criteria");
            }
        });
        store.resumeRun(runId, "resumed after " + cause + " by " + keyId);
    }

    /** Drives a RUNNING run from its persisted node states (after an authorized resumption). */
    public RunOutcome continueRun(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
        return verifiedIfTerminal(runId, drive(runId, executors, taskExecutor));
    }

    /** T118: the human decision at a gate node, with what it rejected and the artifact versions it was taken on. */
    private void recordGateDecision(String runId, String node, ApprovalService.Approval approval) {
        boolean approved = approval.status() == ApprovalService.Decision.APPROVED;
        String rationale = approval.reason();
        List<String> rejected;
        switch (approval.kind()) {
            case CLARIFICATION -> rejected = approved
                    ? List.of("implement the ambiguous requirement as written (unsafe, FR-035)")
                    : List.of("proceed with the proposed clarification");
            case CHANGE_APPROVAL -> rejected = approved
                    ? List.of("keep the approved requirement unchanged")
                    : List.of("apply the requested change to the approved requirement");
            case RELEASE_AUTHORIZATION -> {
                var release = releases.find(runId);
                rationale = release.map(r -> "recommendation " + r.recommendation()
                        + (r.blockingReasons().isEmpty() ? "" : "; blocking: " + String.join("; ", r.blockingReasons())) + "; ").orElse("")
                        + approval.reason();
                rejected = List.of(approved ? "withhold the release" : "authorize the release");
            }
            default -> rejected = approved ? List.of("start implementation without an approved design (FR-024)")
                    : List.of("approve the design as proposed");
        }
        lineage.record(runId, approval.kind().name(), AuditEntry.ActorType.HUMAN, approval.kind() + " at " + node + " "
                + approval.status() + " by " + approval.approverKeyId() + " (approval " + approval.id() + ") [evidence "
                + lineage.evidenceClassOf(runId, approval.id()) + "]", rationale, rejected,
                lineage.currentArtifacts(runId));
    }

    /**
     * T086 (FR-048, ADR-005): after a run turns terminal, re-verify the whole chain, terminal appends included, and append
     * the result as the run's last event before the outcome is reported. The verification event itself is covered by
     * any later GET /verify.
     */
    private RunOutcome verifiedIfTerminal(String runId, RunOutcome outcome) {
        if (!outcome.runStatus().isTerminal()) {
            return outcome;
        }
        ChainVerification verification = chain.verify(runId);
        chain.append(runId, verification.intact()
                ? new AuditEntry(AuditEntry.ActorType.SYSTEM, "CHAIN_VERIFIED", clock.now(), runId, "INTACT",
                        "terminal re-verification: " + verification.eventCount() + " events intact; head " + chain.headHash(runId))
                : new AuditEntry(AuditEntry.ActorType.SYSTEM, "CHAIN_VERIFICATION_FAILED", clock.now(), runId, "BROKEN",
                        "terminal re-verification: broken at position " + verification.failedPosition() + " (" + verification.check() + ")"));
        return outcome;
    }

    private RunOutcome settleAndContinue(String runId, RunStatus runStatus, Map<String, StageExecutor> executors,
            TaskExecutor taskExecutor) {
        boolean progressed = false;
        for (Map.Entry<String, NodeStatus> node : store.nodeStatuses(runId).entrySet()) {
            if (node.getValue() != NodeStatus.AWAITING_APPROVAL) {
                continue;
            }
            ApprovalService.Approval approval = approvals.latestForNode(runId, node.getKey());
            if (approval.kind() == ApprovalKind.RELEASE_AUTHORIZATION && approval.status() != ApprovalService.Decision.PENDING
                    && approval.status() != ApprovalService.Decision.ESCALATED) {
                // FR-037: the human G7 decision is the release decision; the system only recommended
                releases.recordDecision(runId, approval.status() == ApprovalService.Decision.APPROVED, approval.approverKeyId(),
                        approval.decidedAt(), "approval:" + approval.id());
            }
            if (approval.status() == ApprovalService.Decision.APPROVED || approval.status() == ApprovalService.Decision.REJECTED) {
                recordGateDecision(runId, node.getKey(), approval);
            }
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
                String governed = "governed termination after " + reason + " (rework cycles deferred, L-SC-04)";
                if (approval.kind() == ApprovalKind.RELEASE_AUTHORIZATION) {
                    store.finishRun(runId, runStatus, RunStatus.TERMINATED, "RELEASE_REJECTED", governed);
                } else {
                    store.transitionRun(runId, runStatus, RunStatus.TERMINATED, governed);
                }
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
            if (expired.kind() == ApprovalKind.REPLAN_ACCEPTANCE) {
                replanning.expire(runId, expired.id()); // the prior revision stays in force; the run is not stopped
                continue;
            }
            if (approvals.latestForNode(runId, expired.node()).id() != expired.id()) {
                continue; // superseded by a newer request for the node (for example after an accepted replan)
            }
            if (store.nodeStatuses(runId).get(expired.node()) == NodeStatus.AWAITING_APPROVAL) {
                store.transitionNode(runId, expired.node(), NodeStatus.AWAITING_APPROVAL, NodeStatus.SAFE_STOPPED,
                        "approval " + expired.id() + " expired after escalation (FP-4)");
            }
            RunStatus runStatus = store.runStatus(runId);
            if (runStatus == RunStatus.AWAITING_APPROVAL || runStatus == RunStatus.SUSPENDED_CLARIFICATION
                    || runStatus == RunStatus.RUNNING) {
                store.safeStopRun(runId, runStatus, "APPROVAL_TIMEOUT", "approval " + expired.id() + " (" + expired.gate()
                        + ") expired without a decision; silence is never approval");
            }
        }
    }

    /**
     * Records each node failure for MTTR (FR-031, plan section 7): detection on FAILED, recovery start and mechanism (FP-1
     * retry, FP-2 fallback) on RETRY_WAITING, completion on SUCCEEDED. A failure the path never recovers from stays
     * unrecovered (excluded from the primary MTTR and reported separately, T079).
     */
    private final class FailureTracking {

        private final String runId;
        private final Map<String, Long> open = new HashMap<>();

        FailureTracking(String runId) {
            this.runId = runId;
        }

        void onTransition(String node, NodeStatus to, String reason) {
            Instant now = clock.now();
            if (to == NodeStatus.FAILED && !open.containsKey(node)) {
                open.put(node, failureRecorder.detected(runId, store.nodeId(runId, node), now));
            } else if (to == NodeStatus.RETRY_WAITING && open.containsKey(node)) {
                RecoveryMechanism mechanism = reason != null && reason.startsWith("FP-2") ? RecoveryMechanism.FP_2_FALLBACK : RecoveryMechanism.FP_1_RETRY;
                long id = open.get(node);
                if (failureRecorder.find(id).recoveryStartAt() == null) {
                    failureRecorder.recoveryStarted(id, now, mechanism);
                } else {
                    failureRecorder.switchMechanism(id, mechanism);
                }
            } else if (to == NodeStatus.SUCCEEDED && open.containsKey(node)) {
                failureRecorder.recovered(open.remove(node), now);
            }
        }
    }

    private RunOutcome drive(String runId, Map<String, StageExecutor> executors, TaskExecutor taskExecutor) {
        Map<String, StageTask> stageTasks = new HashMap<>();
        Map<String, StageTask> fallbacks = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            if (node.equals(SdlcWorkflow.IMPLEMENTATION_NODE)) {
                stageTasks.put(node, ctx -> runTasks(runId, taskExecutor));
            } else {
                StageExecutor executor = executors.get(node);
                if (executor == null) {
                    throw new IllegalArgumentException("no executor registered for " + node);
                }
                StageExecutor primary = executor instanceof StageExecutor.WithFallback f ? f.primary() : executor;
                stageTasks.put(node, ctx -> primary.execute(new RunContext(runId, node)));
                if (executor instanceof StageExecutor.WithFallback f) {
                    fallbacks.put(node, ctx -> f.fallback().execute(new RunContext(runId, node)));
                }
            }
        }
        FailureTracking failures = new FailureTracking(runId);
        GraphScheduler.RunResult result = new GraphScheduler(stagePool, (node, from, to, reason) -> {
            store.transitionNode(runId, node, from, to, reason);
            failures.onTransition(node, to, reason);
        }, retryPolicy).execute(SdlcWorkflow.graph(), stageTasks, fallbacks, store.nodeStatuses(runId));
        if (result.succeeded()) {
            if (releases.recommendation(runId).filter("NOT_READY"::equals).isPresent()) {
                store.finishRun(runId, RunStatus.RUNNING, RunStatus.TERMINATED, "NOT_READY",
                        "graph finished with a NOT READY determination; release authorization was blocked (FR-033)");
                return new RunOutcome(RunStatus.TERMINATED, "NOT_READY");
            }
            List<CompletionGate.Condition> unmet = completionGate.unmet(runId);
            if (!unmet.isEmpty()) {
                // FR-044: no completion declaration without every Constitution XI condition evidenced; resumable once it is
                String reason = "completion gate: no evidence for " + unmet;
                chain.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "COMPLETION_BLOCKED", clock.now(), runId, "BLOCKED", reason));
                store.safeStopRun(runId, RunStatus.RUNNING, "COMPLETION_GATE", reason);
                return new RunOutcome(RunStatus.SAFE_STOPPED, reason);
            }
            var decided = releases.find(runId).filter(d -> "APPROVED".equals(d.decision()));
            if (decided.isPresent()) {
                store.finishRun(runId, RunStatus.RUNNING, RunStatus.COMPLETED, decided.get().recommendation(),
                        "all stages succeeded; release authorized by " + decided.get().approverKeyId());
            } else {
                store.transitionRun(runId, RunStatus.RUNNING, RunStatus.COMPLETED, "all stages succeeded or not applicable");
            }
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
            store.safeStopRun(runId, RunStatus.RUNNING, "FAILURE", "safe-stop after " + reason);
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
