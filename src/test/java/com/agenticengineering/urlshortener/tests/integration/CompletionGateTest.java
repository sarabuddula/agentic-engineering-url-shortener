package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.CompletionGate;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T101 (FR-044, Constitution XI): the engine never declares a run COMPLETED unless all ten conditions are evidenced by
 * the run's records. Each case omits one condition's evidence and the run safe-stops at the completion gate naming
 * exactly that condition; with every condition evidenced the run completes. Stages are labelled fixtures; the G7 release
 * approval is a real gate decision (SIMULATED evidence).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(CompletionEvidenceFixture.class)
class CompletionGateTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private CompletionEvidenceFixture evidence;

    @Autowired
    private CompletionGate gate;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private GateExecutor.Factory gates;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRunWithEveryConditionEvidencedCompletes() {
        String runId = run(EnumSet.noneOf(CompletionGate.Condition.class));

        assertThat(gate.unmet(runId)).isEmpty();
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(CompletionGate.Condition.class)
    void aMissingConditionBlocksCompletion(CompletionGate.Condition missing) {
        String runId = run(EnumSet.of(missing));

        assertThat(gate.unmet(runId)).containsExactly(missing);
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(jdbc.queryForObject("SELECT SAFE_STOP_CAUSE FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId))
                .isEqualTo("COMPLETION_GATE");
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("COMPLETION_BLOCKED") && e.reason().contains(missing.name()));
    }

    /** Runs to N13 and decides G7, unless approval is the omitted condition; EVIDENCE_AVAILABLE is omitted by tampering. */
    private String run(Set<CompletionGate.Condition> omitted) {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        evidence.recordExcept(runId, omitted);
        boolean approve = !omitted.contains(CompletionGate.Condition.APPROVAL_RECORDED);
        Map<String, StageExecutor> executors = executors(approve);
        engine.execute(runId, executors, task -> StageResult.succeeded());
        if (omitted.contains(CompletionGate.Condition.EVIDENCE_AVAILABLE)) {
            jdbc.update("UPDATE AUDIT_EVENT SET REASON = 'rewritten' WHERE RUN_ID = ? AND SEQ = 2", runId);
        }
        if (approve) {
            ApprovalService.Approval release = approvals.pending(runId).stream()
                    .filter(a -> a.kind() == ApprovalKind.RELEASE_AUTHORIZATION).findFirst().orElseThrow();
            approvals.decide(runId, release.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, AuditEntry.ActorType.HUMAN, true,
                    "release approved", ApprovalService.EvidenceClass.SIMULATED);
            engine.resume(runId, executors, task -> StageResult.succeeded());
        }
        return runId;
    }

    private Map<String, StageExecutor> executors(boolean releaseGate) {
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
        executors.put("N12", ctx -> StageResult.succeeded(releaseGate ? Set.of("N13") : Set.of()));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        executors.remove("N08");
        return executors;
    }
}
