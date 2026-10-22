package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanningService;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import com.agenticengineering.urlshortener.tests.support.ReplanFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * T118 (FR-023, FR-029; data-model DECISION_LINEAGE): every recorded human decision - clarification (N03), design
 * approval (N07), release decision (G7), replan acceptance or rejection, policy exception - leaves a lineage row with the
 * decision, rationale, rejected alternatives and artifact refs (id@version#hash), and an audit event DECISION_RECORDED
 * that points at it, so the history can return the rationale. Decisions here are executed by the test (SIMULATED).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PolicyRunFixture.class, ReplanFixture.class})
class DecisionLineageTest {

    @Autowired
    private DecisionLineage lineage;

    @Autowired
    private PolicyRunFixture policyRun;

    @Autowired
    private ReplanFixture replanRun;

    @Autowired
    private ReplanningService replanning;

    @Autowired
    private PolicyExceptionService exceptions;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private GateExecutor.Factory gates;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private WorkflowStore store;

    @Test
    void designAndReleaseDecisionsAreRecorded() {
        var evidence = PolicyRunFixture.goodEvidence();
        String runId = policyRun.runThroughPolicy(evidence);
        policyRun.decideRelease(runId, evidence, true);

        List<DecisionLineage.Decision> decisions = lineage.forRun(runId);

        assertThat(decisions).anyMatch(d -> d.decision().contains("DESIGN_APPROVAL") && d.decision().contains("APPROVED")
                && d.decision().contains("test-approver") && !d.rejectedAlternatives().isEmpty());
        assertThat(decisions).anyMatch(d -> d.decision().contains("RELEASE_AUTHORIZATION") && d.decision().contains("APPROVED")
                && d.rationale().contains("READY"));
        assertPointedAtByTheAudit(runId, decisions);
    }

    @Test
    void aClarificationAnswerIsRecorded() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(Set.of("N03")));
        executors.put("N03", gates.gate(ApprovalKind.CLARIFICATION));
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.remove("N08");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        ApprovalService.Approval clarification = approvals.pending(runId).get(0);
        approvals.decide(runId, clarification.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true,
                "clarification: links = short links", ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors, task -> StageResult.succeeded());

        assertThat(lineage.forRun(runId)).anyMatch(d -> d.decision().contains("CLARIFICATION") && d.rationale().contains("links = short links"));
    }

    @Test
    void replanDecisionsCarryTheArtifactVersionsTheyConcern() {
        String accepted = replanRun.runToDesignGate();
        replanRun.changeUpstream(accepted);
        replanning.request(accepted, "test-engineer");
        decide(accepted, ApprovalKind.REPLAN_ACCEPTANCE, "test-approver", KeyIdentity.Role.APPROVER, true);
        replanRun.resume(accepted);
        ArtifactStore.Artifact current = artifacts.latest(accepted, "IMPACT_ANALYSIS").orElseThrow();

        String rejected = replanRun.runToDesignGate();
        replanRun.changeUpstream(rejected);
        replanning.request(rejected, "test-engineer");
        decide(rejected, ApprovalKind.REPLAN_ACCEPTANCE, "test-approver", KeyIdentity.Role.APPROVER, false);
        replanRun.resume(rejected);

        assertThat(lineage.forRun(accepted)).anyMatch(d -> d.decision().contains("replan") && d.decision().contains("ACCEPTED")
                && d.artifactRefs().contains(current.id() + "@" + current.version() + "#" + current.contentHash()));
        assertThat(lineage.forRun(rejected)).anyMatch(d -> d.decision().contains("replan") && d.decision().contains("REJECTED")
                && !d.rejectedAlternatives().isEmpty());
    }

    @Test
    void anApprovedPolicyExceptionIsRecorded() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "license review pending", "this run",
                "test-engineer");
        exceptions.approve(runId, requested.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, "pinned version",
                Instant.now().plus(Duration.ofDays(1)), ApprovalService.EvidenceClass.SIMULATED);

        assertThat(lineage.forRun(runId)).anyMatch(d -> d.decision().contains("DEP-01") && d.rationale().contains("pinned version"));
    }

    private void decide(String runId, ApprovalKind kind, String keyId, KeyIdentity.Role role, boolean approve) {
        ApprovalService.Approval approval = approvals.pending(runId).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
        approvals.decide(runId, approval.id(), keyId, role, AuditEntry.ActorType.HUMAN, approve, approve ? "approved" : "rejected",
                ApprovalService.EvidenceClass.SIMULATED);
    }

    private void assertPointedAtByTheAudit(String runId, List<DecisionLineage.Decision> decisions) {
        for (DecisionLineage.Decision decision : decisions) {
            assertThat(store.history(runId)).anyMatch(e -> e.action().equals("DECISION_RECORDED")
                    && ("decision:" + decision.id()).equals(e.affected()));
        }
    }
}
