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
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.stages.PolicyStageExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T062: a failed mandatory policy check blocks release authorization (FR-033, FR-037, Constitution VI): N12 persists
 * NOT READY with blocking reasons and a RELEASE_AUTHORIZATION_BLOCKED audit event, N13 is not taken, N14 still runs
 * (reporting only) and the run ends TERMINATED with the NOT READY outcome. Evidence is supplied by a fixture provider.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PolicyBlockingTest {

    private static final List<String> AUTOMATED = List.of("SEC-01", "SEC-02", "SEC-03", "CMP-02", "PRV-01", "AUD-01", "AUD-02",
            "DEP-01", "DEP-02", "LIC-01", "CHG-01", "CHG-02", "CHG-03");

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
    void aMandatoryFailureBlocksAuthorizationAndEndsNotReady() {
        Map<String, Evidence> evidence = goodEvidence();
        evidence.put("DEP-01", Evidence.notOk("pom.xml: com.example:undeclared-lib not in docs/compliance/licenses.xml"));
        String runId = runToPolicy(evidence);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertThat(jdbc.queryForObject("SELECT TERMINAL_OUTCOME FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId)).isEqualTo("NOT_READY");
        assertThat(store.nodeStatuses(runId)).containsEntry("N12", NodeStatus.SUCCEEDED).containsEntry("N13", NodeStatus.NOT_APPLICABLE)
                .containsEntry("N14", NodeStatus.SUCCEEDED);
        Map<String, Object> decision = jdbc.queryForMap("SELECT RECOMMENDATION, BLOCKING_REASONS, DECISION FROM RELEASE_DECISION WHERE RUN_ID = ?", runId);
        assertThat(decision).containsEntry("RECOMMENDATION", "NOT_READY").containsEntry("DECISION", "PENDING");
        assertThat((String) decision.get("BLOCKING_REASONS")).contains("DEP-01");
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("RELEASE_AUTHORIZATION_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = ? AND GATE = 'G7'", Integer.class, runId))
                .as("the release gate is never offered").isZero();
        assertThat(jdbc.queryForObject("SELECT OUTCOME FROM POLICY_EVALUATION WHERE RUN_ID = ? AND CHECK_ID = 'DEP-01'", String.class, runId))
                .isEqualTo("FAIL");
    }

    @Test
    void withoutMandatoryFailuresTheReleaseGateIsOffered() {
        String runId = runToPolicy(goodEvidence());

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.AWAITING_APPROVAL);
        assertThat(approvals.pending(runId).get(0).kind()).isEqualTo(ApprovalKind.RELEASE_AUTHORIZATION);
    }

    /** Runs to N12 with the given evidence: the design gate is approved by a distinct APPROVER (SIMULATED evidence). */
    private String runToPolicy(Map<String, Evidence> evidence) {
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

        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        ApprovalService.Approval design = approvals.pending(runId).get(0);
        approvals.decide(runId, design.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true,
                "design approved", ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors, task -> StageResult.succeeded());
        return runId;
    }

    private static Map<String, Evidence> goodEvidence() {
        Map<String, Evidence> evidence = new HashMap<>();
        AUTOMATED.forEach(id -> evidence.put(id, Evidence.ok("fixture:" + id)));
        return evidence;
    }
}
