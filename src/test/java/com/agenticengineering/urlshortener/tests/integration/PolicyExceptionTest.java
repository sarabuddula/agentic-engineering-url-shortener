package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.stages.PolicyStageExecutor;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T063: the policy-exception path (FR-033, Constitution VI; ADR-008 G5 = RELEASE_OWNER). A request records the
 * exception without authority and opens a G5 approval; only a distinct human release owner approves it, recording
 * approving authority, compensating control, approved_at and expiry. At N12 a failing check covered by an approved,
 * unexpired exception is EXCEPTION_REQUESTED and does not block; an unapproved or expired one blocks (NOT READY).
 * Approvals here are executed by the test and labelled SIMULATED.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PolicyExceptionTest {

    private static final List<String> AUTOMATED = List.of("SEC-01", "SEC-02", "SEC-03", "CMP-02", "PRV-01", "AUD-01", "AUD-02",
            "DEP-01", "DEP-02", "LIC-01", "CHG-01", "CHG-02", "CHG-03");

    @Autowired
    private PolicyExceptionService exceptions;

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
    private PolicyStageExecutor.Factory policyStages;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRequestRecordsTheExceptionWithoutAuthorityAndOpensAG5Approval() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "transitive lib pending review",
                "this run only", "test-engineer");

        assertThat(requested.approved()).isFalse();
        assertThat(requested.approvingAuthorityKeyId()).isNull();
        ApprovalService.Approval g5 = approvals.find(runId, requested.approvalId());
        assertThat(g5.kind()).isEqualTo(ApprovalKind.POLICY_EXCEPTION);
        assertThat(g5.status()).isEqualTo(ApprovalService.Decision.PENDING);
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("POLICY_EXCEPTION_REQUESTED");
    }

    @Test
    void aDistinctReleaseOwnerApprovalRecordsEveryField() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "transitive lib pending review",
                "this run only", "test-engineer");
        Instant expiry = Instant.now().plus(Duration.ofDays(1));

        PolicyExceptionService.PolicyException approved = exceptions.approve(runId, requested.id(), "test-release-owner",
                KeyIdentity.Role.RELEASE_OWNER, "pinned version, no network use", expiry, ApprovalService.EvidenceClass.SIMULATED);

        assertThat(approved.approved()).isTrue();
        assertThat(approved.checkId()).isEqualTo("DEP-01");
        assertThat(approved.reason()).isEqualTo("transitive lib pending review");
        assertThat(approved.scope()).isEqualTo("this run only");
        assertThat(approved.approvingAuthorityKeyId()).isEqualTo("test-release-owner");
        assertThat(approved.compensatingControl()).isEqualTo("pinned version, no network use");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.expiresAt()).isEqualTo(expiry.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(approvals.find(runId, requested.approvalId()).status()).isEqualTo(ApprovalService.Decision.APPROVED);
        assertThat(jdbc.queryForObject("SELECT EVIDENCE_CLASS FROM APPROVAL WHERE ID = ?", String.class, requested.approvalId()))
                .isEqualTo("SIMULATED");
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("POLICY_EXCEPTION_APPROVED");
    }

    @Test
    void approvalNeedsACompensatingControlAndAFutureExpiry() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "reason", "scope", "test-engineer");

        assertThatThrownBy(() -> exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, " ",
                Instant.now().plus(Duration.ofDays(1)), ApprovalService.EvidenceClass.SIMULATED)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER,
                "control", Instant.now().minusSeconds(60), ApprovalService.EvidenceClass.SIMULATED)).isInstanceOf(InvalidRequestException.class);
        assertThat(exceptions.find(runId, requested.id()).approved()).isFalse();
    }

    @Test
    void selfApprovalOfAnExceptionIsDenied() {
        String runId = engine.createRun("api:test-release-owner", "POL-2026-10-18-v1");
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "reason", "scope", "test-release-owner");

        assertThatThrownBy(() -> exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER,
                "control", Instant.now().plus(Duration.ofDays(1)), ApprovalService.EvidenceClass.SIMULATED))
                .isInstanceOf(SelfApprovalDeniedException.class);
        assertThat(exceptions.find(runId, requested.id()).approved()).isFalse();
    }

    @Test
    void anApprovedUnexpiredExceptionLetsTheRunReachTheReleaseGate() {
        String runId = runToDesignGate();
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "reason", "this run", "test-engineer");
        exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, "control",
                Instant.now().plus(Duration.ofDays(1)), ApprovalService.EvidenceClass.SIMULATED);

        resumePastDesign(runId);

        assertThat(jdbc.queryForMap("SELECT OUTCOME, EXCEPTION_ID FROM POLICY_EVALUATION WHERE RUN_ID = ? AND CHECK_ID = 'DEP-01'", runId))
                .containsEntry("OUTCOME", "EXCEPTION_REQUESTED").containsEntry("EXCEPTION_ID", requested.id());
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.AWAITING_APPROVAL);
    }

    @Test
    void anUnapprovedExceptionStillBlocks() {
        String runId = runToDesignGate();
        exceptions.request(runId, "DEP-01", "reason", "this run", "test-engineer");

        resumePastDesign(runId);

        assertNotReady(runId, "not approved");
    }

    @Test
    void anExpiredExceptionStillBlocks() {
        String runId = runToDesignGate();
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "reason", "this run", "test-engineer");
        exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, "control",
                Instant.now().plus(Duration.ofDays(1)), ApprovalService.EvidenceClass.SIMULATED);
        jdbc.update("UPDATE POLICY_EXCEPTION SET EXPIRES_AT = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE ID = ?", requested.id());

        resumePastDesign(runId);

        assertNotReady(runId, "expired");
    }

    private void assertNotReady(String runId, String why) {
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.NOT_APPLICABLE);
        String reasons = jdbc.queryForObject("SELECT BLOCKING_REASONS FROM RELEASE_DECISION WHERE RUN_ID = ?", String.class, runId);
        assertThat(reasons).contains("DEP-01").contains(why);
    }

    private String runToDesignGate() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(), task -> StageResult.succeeded());
        return runId;
    }

    private void resumePastDesign(String runId) {
        ApprovalService.Approval design = approvals.pending(runId).stream().filter(a -> a.kind() == ApprovalKind.DESIGN_APPROVAL)
                .findFirst().orElseThrow();
        approvals.decide(runId, design.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true,
                "design approved", ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors(), task -> StageResult.succeeded());
    }

    /** DEP-01 fails in every run here; everything else passes. Exceptions come from persisted records, not the provider. */
    private Map<String, StageExecutor> executors() {
        Map<String, Evidence> evidence = new HashMap<>();
        AUTOMATED.forEach(id -> evidence.put(id, Evidence.ok("fixture:" + id)));
        evidence.put("DEP-01", Evidence.notOk("pom.xml: com.example:undeclared-lib not in docs/compliance/licenses.xml"));
        PolicyInput input = new PolicyInput(evidence, Map.of(
                "PRV-02", new PolicyInput.Attestation("test-release-owner", "fixture"),
                "LIC-02", new PolicyInput.Attestation("test-release-owner", "fixture")), Map.of());
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
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.put("N12", policyStages.withEvidence(runId -> input));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        executors.remove("N08");
        return executors;
    }
}
