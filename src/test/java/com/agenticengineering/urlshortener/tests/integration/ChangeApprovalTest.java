package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutorRegistry;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.stages.RequirementIngestion;
import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T119 (FR-024, C-005, NFR-010; ADR-008): a change to an approved requirement - SCN-B changes A-006 - needs a
 * CHANGE_APPROVAL by an APPROVER other than the requester, requested at N04 with the impact analysis, before any
 * decomposition or implementation. Approval continues to the design gate; rejection ends the run; the decision enters
 * the lineage. Production executors for N01, N02, N04, N05 and the gates; N06, N09-N11, N14 are labelled fixtures.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChangeApprovalTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private RequirementIngestion ingestion;

    @Autowired
    private StageExecutorRegistry registry;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private DecisionLineage lineage;

    @Autowired
    private WorkflowStore store;

    @Test
    void aChangeToAnApprovedRequirementWaitsForChangeApprovalAtN04() {
        String runId = start();

        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.AWAITING_APPROVAL).containsEntry("N05", NodeStatus.PENDING);
        ApprovalService.Approval change = pending(runId);
        assertThat(change.kind()).isEqualTo(ApprovalKind.CHANGE_APPROVAL);
        assertThat(change.reason()).contains("A-006").contains(artifacts.latest(runId, "IMPACT_ANALYSIS").orElseThrow().contentHash());
    }

    @Test
    void approvalByADistinctApproverContinuesToTheDesignGate() {
        String runId = start();

        decide(runId, "test-approver", KeyIdentity.Role.APPROVER, true);
        engine.resume(runId, executors(), task -> StageResult.succeeded());

        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.SUCCEEDED).containsEntry("N05", NodeStatus.SUCCEEDED)
                .containsEntry("N07", NodeStatus.AWAITING_APPROVAL);
        assertThat(lineage.forRun(runId)).anyMatch(d -> d.decision().contains("CHANGE_APPROVAL") && d.decision().contains("APPROVED"));
    }

    @Test
    void theRequesterAndOtherRolesCannotApproveTheChange() {
        String runId = start();

        assertThatThrownBy(() -> decide(runId, "test-engineer", KeyIdentity.Role.APPROVER, true)).isInstanceOf(SelfApprovalDeniedException.class);
        assertThatThrownBy(() -> decide(runId, "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, true))
                .isInstanceOf(ForbiddenException.class);
        assertThat(pending(runId).status()).isEqualTo(ApprovalService.Decision.PENDING);
    }

    @Test
    void aRejectedChangeEndsTheRunBeforeDecomposition() {
        String runId = start();

        decide(runId, "test-approver", KeyIdentity.Role.APPROVER, false);
        engine.resume(runId, executors(), task -> StageResult.succeeded());

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.FAILED).containsEntry("N05", NodeStatus.PENDING);
    }

    private String start() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ingestion.ingest(runId, RequirementIngestion.normalize(ScenarioFixtures.scnB()));
        engine.execute(runId, executors(), task -> StageResult.succeeded());
        return runId;
    }

    private void decide(String runId, String keyId, KeyIdentity.Role role, boolean approve) {
        approvals.decide(runId, pending(runId).id(), keyId, role, AuditEntry.ActorType.HUMAN, approve, approve ? "change approved" : "rejected",
                ApprovalService.EvidenceClass.SIMULATED);
    }

    private ApprovalService.Approval pending(String runId) {
        return approvals.pending(runId).stream().filter(a -> a.kind() == ApprovalKind.CHANGE_APPROVAL).findFirst().orElseThrow();
    }

    private Map<String, StageExecutor> executors() {
        Map<String, StageExecutor> executors = registry.executors();
        for (String node : List.of("N06", "N09", "N10", "N11", "N14")) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        return executors;
    }
}
