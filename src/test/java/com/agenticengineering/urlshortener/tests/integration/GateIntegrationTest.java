package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T061 (engine side): human gates pause the graph and the run resumes from persisted state after the decision
 * (FR-024, FR-025, FR-028, SC-004). Gate nodes use the real GateExecutor; the other stages are labelled fixtures.
 * Requirements: US-002 (human reviewer approves, rejects and governs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CompletionEvidenceFixture.class)
class GateIntegrationTest {

    private static final TaskExecutor TASKS = task -> StageResult.succeeded();

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private GateExecutor.Factory gates;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CompletionEvidenceFixture completionEvidence;

    @Test
    void theDesignGatePausesTheRunAndBlocksImplementation() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        OrchestrationEngine.RunOutcome outcome = engine.execute(runId, executors(Set.of()), TASKS);

        assertThat(outcome.runStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.AWAITING_APPROVAL).containsEntry("N08", NodeStatus.PENDING);
        ApprovalService.Approval pending = approvals.pending(runId).get(0);
        assertThat(pending.kind()).isEqualTo(ApprovalKind.DESIGN_APPROVAL);
        assertThat(pending.node()).isEqualTo("N07");
        assertThat(pending.requesterKeyId()).isEqualTo("test-engineer");
    }

    @Test
    void resumingWithoutADecisionProgressesNothingAndIsAudited() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(Set.of()), TASKS);

        OrchestrationEngine.RunOutcome again = engine.resume(runId, executors(Set.of()), TASKS);

        assertThat(again.runStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(store.nodeStatuses(runId)).containsEntry("N08", NodeStatus.PENDING);
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("GATE_BLOCKED");
    }

    @Test
    void approvalsAtBothGatesCompleteTheRun() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        completionEvidence.recordAll(runId); // FR-044 (T101): labelled fixture evidence so the run may complete
        engine.execute(runId, executors(Set.of()), TASKS);
        decide(runId, "test-approver", KeyIdentity.Role.APPROVER, true);

        OrchestrationEngine.RunOutcome afterDesign = engine.resume(runId, executors(Set.of()), TASKS);
        assertThat(afterDesign.runStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.SUCCEEDED).containsEntry("N13", NodeStatus.AWAITING_APPROVAL);
        assertThat(approvals.pending(runId).get(0).kind()).isEqualTo(ApprovalKind.RELEASE_AUTHORIZATION);

        decide(runId, "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, true);
        OrchestrationEngine.RunOutcome done = engine.resume(runId, executors(Set.of()), TASKS);

        assertThat(done.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.SUCCEEDED).containsEntry("N14", NodeStatus.SUCCEEDED);
        assertThat(tasks.statuses(runId).values()).containsOnly("SUCCEEDED");
    }

    @Test
    void aRejectionEndsThePathInGovernedTermination() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(Set.of()), TASKS);
        decide(runId, "test-approver", KeyIdentity.Role.APPROVER, false);

        OrchestrationEngine.RunOutcome outcome = engine.resume(runId, executors(Set.of()), TASKS);

        assertThat(outcome.runStatus()).isEqualTo(RunStatus.TERMINATED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.FAILED).containsEntry("N08", NodeStatus.PENDING);
        assertThat(outcome.reason()).contains("rejected");
    }

    @Test
    void anExpiredApprovalSafeStopsThePathAndTheRun() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(Set.of()), TASKS);
        long approvalId = approvals.pending(runId).get(0).id();
        jdbc.update("UPDATE APPROVAL SET DECISION = 'ESCALATED', TIMEOUT_AT = DATEADD('MINUTE', -20, CURRENT_TIMESTAMP),"
                + " SECOND_WINDOW_TIMEOUT_AT = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE ID = ?", approvalId);

        engine.handleTimeouts();

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.SAFE_STOPPED);
    }

    @Test
    void theClarificationGateSuspendsTheRun() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        OrchestrationEngine.RunOutcome outcome = engine.execute(runId, executors(Set.of("N03")), TASKS);

        assertThat(outcome.runStatus()).isEqualTo(RunStatus.SUSPENDED_CLARIFICATION);
        assertThat(store.nodeStatuses(runId)).containsEntry("N03", NodeStatus.AWAITING_APPROVAL).containsEntry("N05", NodeStatus.PENDING);
        assertThat(approvals.pending(runId).get(0).kind()).isEqualTo(ApprovalKind.CLARIFICATION);
    }

    private void decide(String runId, String keyId, KeyIdentity.Role role, boolean approve) {
        ApprovalService.Approval pending = approvals.pending(runId).get(0);
        approvals.decide(runId, pending.id(), keyId, role, AuditEntry.ActorType.HUMAN, approve, approve ? "approved" : "rejected",
                ApprovalService.EvidenceClass.SIMULATED);
    }

    /** Real gates at N03, N07 and N13; labelled fixtures elsewhere; N02 selects the given branches. */
    private Map<String, StageExecutor> executors(Set<String> n02Branches) {
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(n02Branches));
        executors.put("N03", gates.gate(ApprovalKind.CLARIFICATION));
        executors.put("N05", ctx -> {
            if (tasks.statuses(ctx.runId()).isEmpty()) {
                tasks.saveDecomposition(ctx.runId(), List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of())));
            }
            return StageResult.succeeded();
        });
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.put("N12", ctx -> StageResult.succeeded(Set.of("N13")));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        executors.remove("N08");
        return executors;
    }
}
