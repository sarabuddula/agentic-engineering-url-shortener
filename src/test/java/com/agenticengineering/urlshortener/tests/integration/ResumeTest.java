package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.ResumeConflictException;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureEvent;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureRecorder;
import com.agenticengineering.urlshortener.orchestration.reliability.RecoveryMechanism;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T110 engine side (FR-028; human decision 2026-10-19): a safe-stopped run resumes only through a recorded resumption
 * that checks its cause. Ungoverned stops (a stage failure) may be resumed by the ENGINEER; governed stops (approval
 * timeout, boot recovery) need an APPROVER other than the run's requester; a completion-gate stop resumes only once
 * the missing evidence exists; anything not safe-stopped is a conflict. Stopped nodes go back through READY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CompletionEvidenceFixture.class)
class ResumeTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private GateExecutor.Factory gates;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private CompletionEvidenceFixture evidence;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FailureRecorder failureRecorder;

    @Test
    void anEngineerResumesAFailureStopAndTheFailedStageRunsAgain() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, StageExecutor> executors = fixtures(false);
        executors.put("N06", ctx -> calls.incrementAndGet() == 1 ? StageResult.failed("permanent: design tool crashed") : StageResult.succeeded());
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        assertThat(cause(runId)).isEqualTo("FAILURE");

        engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, executors, task -> StageResult.succeeded());

        assertThat(store.nodeStatuses(runId)).containsEntry("N06", NodeStatus.SUCCEEDED).containsEntry("N14", NodeStatus.SUCCEEDED);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("RUN_RESUMED") && e.reason().contains("FAILURE"));
    }

    /**
     * Defect found in the SCN-A demonstration (2026-10-22, run-934a50a1...): N08 failed before its commits existed, the
     * run safe-stopped and was resumed, N08 then succeeded - but the failure stayed unrecovered, because failures were
     * tracked per drive in memory. The completion gate then refused SECURITY_AND_RELIABILITY_CHECKED. A failure the
     * resumed run recovers from must be recorded as recovered (FP-4 safe-stop and resumption), with the time spent
     * waiting for the human resumption counted as governance wait.
     */
    @Test
    void aFailureRecoveredAfterAResumptionIsRecordedAsRecovered() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, StageExecutor> executors = fixtures(false);
        executors.put("N06", ctx -> calls.incrementAndGet() == 1 ? StageResult.failed("permanent: design tool crashed") : StageResult.succeeded());
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());

        engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, executors, task -> StageResult.succeeded());

        List<FailureEvent> failures = failureRecorder.forRun(runId);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).recovered()).isTrue();
        assertThat(failures.get(0).mechanism()).isEqualTo(RecoveryMechanism.FP_4_SAFE_STOP);
        assertThat(failures.get(0).recoveryStartAt()).isNotNull();
        // GOVERNANCE_WAIT_MS stores whole milliseconds
        assertThat(failures.get(0).governanceWait().toMillis()).isEqualTo(
                Duration.between(failures.get(0).failureDetectedAt(), failures.get(0).recoveryStartAt()).toMillis());
    }

    @Test
    void aGovernedStopNeedsAnApproverOtherThanTheRequester() {
        Map<String, StageExecutor> executors = fixtures(true);
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        long first = approvals.pending(runId).get(0).id();
        jdbc.update("UPDATE APPROVAL SET DECISION = 'ESCALATED', TIMEOUT_AT = DATEADD('MINUTE', -20, CURRENT_TIMESTAMP),"
                + " SECOND_WINDOW_TIMEOUT_AT = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE ID = ?", first);
        engine.handleTimeouts();
        assertThat(cause(runId)).isEqualTo("APPROVAL_TIMEOUT");

        assertThatThrownBy(() -> engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, executors, t -> StageResult.succeeded()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.APPROVER, executors, t -> StageResult.succeeded()))
                .isInstanceOf(SelfApprovalDeniedException.class);
        engine.resumeSafeStopped(runId, "test-approver", KeyIdentity.Role.APPROVER, executors, task -> StageResult.succeeded());

        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.AWAITING_APPROVAL);
        assertThat(approvals.pending(runId)).extracting(ApprovalService.Approval::id).doesNotContain(first).hasSize(1);
    }

    @Test
    void aCompletionGateStopResumesOnlyOnceTheEvidenceExists() {
        Map<String, StageExecutor> executors = fixtures(false);
        executors.put("N12", ctx -> StageResult.succeeded(Set.of("N13")));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        approvals.decide(runId, approvals.pending(runId).get(0).id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER,
                com.agenticengineering.urlshortener.orchestration.audit.AuditEntry.ActorType.HUMAN, true, "release",
                ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors, task -> StageResult.succeeded());
        assertThat(cause(runId)).isEqualTo("COMPLETION_GATE");

        assertThatThrownBy(() -> engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, executors, t -> StageResult.succeeded()))
                .isInstanceOf(ResumeConflictException.class).hasMessageContaining("completion");
        evidence.recordAll(runId);
        engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, executors, task -> StageResult.succeeded());

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void onlySafeStoppedRunsCanBeResumed() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        assertThatThrownBy(() -> engine.resumeSafeStopped(runId, "test-engineer", KeyIdentity.Role.ENGINEER, Map.of(), t -> StageResult.succeeded()))
                .isInstanceOf(ResumeConflictException.class).hasMessageContaining("RUNNING");
    }

    private String cause(String runId) {
        return jdbc.queryForObject("SELECT SAFE_STOP_CAUSE FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
    }

    private Map<String, StageExecutor> fixtures(boolean designGate) {
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
        if (designGate) {
            executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        }
        executors.put("N12", ctx -> StageResult.succeeded(Set.of()));
        executors.remove("N08");
        return executors;
    }
}
