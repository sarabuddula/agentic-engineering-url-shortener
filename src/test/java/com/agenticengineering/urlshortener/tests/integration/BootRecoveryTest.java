package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.reliability.BootRecovery;
import com.agenticengineering.urlshortener.orchestration.reliability.EffectVerifier;
import com.agenticengineering.urlshortener.orchestration.reliability.OperationLog;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T122 (FR-028, PVT-006; plan section 6 safe-stop and resumption): at startup every run found RUNNING or
 * AWAITING_APPROVAL is safe-stopped with cause BOOT_RECOVERY (audited); pending approvals stay pending and nothing
 * resumes on its own. PENDING operations are reconciled by type-specific effect verification: an effect that exists is
 * marked verified and completed (after-effect, before-completion-record case), a missing one stays pending (safe to
 * redo), and an operation type without a rule is unverifiable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BootRecoveryTest {

    @Autowired
    private BootRecovery recovery;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private OperationLog operations;

    @Autowired
    private EffectVerifier verifier;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRunningRunIsSafeStoppedWithItsRunningNode() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        store.transitionNode(runId, "N01", NodeStatus.PENDING, NodeStatus.READY, "fixture");
        store.transitionNode(runId, "N01", NodeStatus.READY, NodeStatus.RUNNING, "fixture: the JVM stopped here");

        recovery.recover();

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(cause(runId)).isEqualTo("BOOT_RECOVERY");
        assertThat(store.nodeStatuses(runId)).containsEntry("N01", NodeStatus.SAFE_STOPPED);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("BOOT_RECOVERY"));
    }

    @Test
    void anAwaitingRunStopsButItsApprovalStaysPendingAndNothingResumes() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        jdbc.update("UPDATE WORKFLOW_RUN SET STATUS = 'AWAITING_APPROVAL' WHERE ID = ?", runId);
        ApprovalService.Approval design = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs");

        recovery.recover();

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(approvals.find(runId, design.id()).status()).isEqualTo(ApprovalService.Decision.PENDING);
        assertThat(engine.resume(runId, Map.of(), task -> StageResult.succeeded()).runStatus()).as("no automatic resume")
                .isEqualTo(RunStatus.SAFE_STOPPED);
    }

    @Test
    void finishedAndStoppedRunsAreLeftAlone() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        store.transitionRun(runId, RunStatus.RUNNING, RunStatus.TERMINATED, "fixture: finished before the restart");
        long events = store.history(runId).size();

        recovery.recover();

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertThat(store.history(runId)).hasSize((int) events);
    }

    @Test
    void anEffectThatExistsIsVerifiedAndAMissingOneStaysPending() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ArtifactStore.Artifact written = artifacts.record(runId, "DESIGN", "design v1", "DETERMINISTIC", "N06");
        String applied = OperationLog.artifactWriteId(runId, written.contentHash());
        jdbc.update("UPDATE OPERATION_RECORD SET STATUS = 'PENDING', COMPLETED_AT = NULL WHERE OPERATION_ID = ?", applied);
        String missing = operations.begin(runId, "N09", "TEST_RUN", "tr:" + runId.substring(4, 20) + ":suite-1");
        jdbc.update("UPDATE WORKFLOW_RUN SET STATUS = 'RUNNING' WHERE ID = ?", runId);

        recovery.recover();

        assertThat(jdbc.queryForMap("SELECT STATUS, EFFECT_VERIFIED FROM OPERATION_RECORD WHERE OPERATION_ID = ?", applied))
                .containsEntry("STATUS", "COMPLETED").containsEntry("EFFECT_VERIFIED", true);
        assertThat(jdbc.queryForMap("SELECT STATUS, EFFECT_VERIFIED FROM OPERATION_RECORD WHERE OPERATION_ID = ?", missing))
                .containsEntry("STATUS", "PENDING").containsEntry("EFFECT_VERIFIED", false);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("OPERATION_RECONCILED") && e.reason().contains(applied));
    }

    @Test
    void anOperationTypeWithoutARuleIsUnverifiable() {
        assertThat(verifier.verify("run-x", "EMAIL_SEND", "op-1", 1L)).isEqualTo(EffectVerifier.Verdict.UNVERIFIABLE);
    }

    private String cause(String runId) {
        return jdbc.queryForObject("SELECT SAFE_STOP_CAUSE FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
    }
}
