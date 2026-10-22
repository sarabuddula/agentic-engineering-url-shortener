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
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanConflictException;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanningService;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.tests.support.ReplanFixture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T071 (FR-029, FR-046, D-006; scope per L-SC-03: no staged-revision dispatch or HELD nodes): an upstream artifact change
 * stages a REPLANNING_EVENT whose re-validation compares the content hashes downstream stages read with the current
 * versions and records what is invalidated; only an APPROVER other than the requester can accept it; acceptance
 * activates run_revision + 1 with the affected nodes to be redone; rejection or expiry leaves the prior revision in force.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ReplanFixture.class)
class ReplanningTest {

    @Autowired
    private ReplanFixture fixture;

    @Autowired
    private ReplanningService replanning;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aChangedUpstreamArtifactStagesAReplanForHumanAcceptance() {
        String runId = fixture.runToDesignGate();
        String v1Hash = artifacts.latest(runId, "IMPACT_ANALYSIS").orElseThrow().contentHash();
        fixture.changeUpstream(runId);

        replanning.request(runId, "test-engineer");

        Map<String, Object> event = event(runId);
        assertThat(event).containsEntry("STATUS", "STAGED").containsEntry("TRIGGER_KIND", "ARTIFACT_VERSION_CHANGE")
                .containsEntry("STAGED_REVISION", 1);
        assertThat((String) event.get("AFFECTED_DOWNSTREAM")).contains("N05", "N06", "N07", "N14").doesNotContain("N01", "N04");
        assertThat(invalidations(runId)).contains("IMPACT_ANALYSIS", v1Hash, "INVALIDATED");
        assertThat(approvals.pending(runId)).extracting(ApprovalService.Approval::kind).contains(ApprovalKind.REPLAN_ACCEPTANCE);
        assertThat(revision(runId)).isZero();
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.AWAITING_APPROVAL);
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("REPLAN_REQUESTED");
    }

    @Test
    void acceptanceByADistinctApproverActivatesTheNextRevision() {
        String runId = fixture.runToDesignGate();
        long oldDesign = pending(runId, ApprovalKind.DESIGN_APPROVAL).id();
        fixture.changeUpstream(runId);
        replanning.request(runId, "test-engineer");

        decide(runId, ApprovalKind.REPLAN_ACCEPTANCE, "test-approver", true);
        fixture.resume(runId);

        assertThat(event(runId)).containsEntry("STATUS", "ACCEPTED");
        assertThat(revision(runId)).isEqualTo(1);
        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.SUCCEEDED).containsEntry("N05", NodeStatus.SUCCEEDED)
                .containsEntry("N07", NodeStatus.AWAITING_APPROVAL);
        assertThat(pending(runId, ApprovalKind.DESIGN_APPROVAL).id()).as("the design gate is re-approved").isNotEqualTo(oldDesign);
        String readNow = jdbc.queryForObject("SELECT i.CONTENT_HASH_AT_READ FROM NODE_INPUT i JOIN STAGE_NODE n ON n.ID = i.NODE_ID"
                + " WHERE n.RUN_ID = ? AND n.NODE_KEY = 'N05' AND n.RUN_REVISION = 1", String.class, runId);
        assertThat(readNow).as("N05 re-read the changed input").isEqualTo(artifacts.latest(runId, "IMPACT_ANALYSIS").orElseThrow().contentHash());
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("REPLAN_ACCEPTED");
    }

    @Test
    void theRequesterCannotAcceptTheirOwnReplan() {
        String runId = fixture.runToDesignGate();
        fixture.changeUpstream(runId);
        replanning.request(runId, "test-approver");

        assertThatThrownBy(() -> decide(runId, ApprovalKind.REPLAN_ACCEPTANCE, "test-approver", true))
                .isInstanceOf(SelfApprovalDeniedException.class);
        assertThat(event(runId)).containsEntry("STATUS", "STAGED");
    }

    @Test
    void rejectionLeavesThePriorRevisionInForce() {
        String runId = fixture.runToDesignGate();
        long design = pending(runId, ApprovalKind.DESIGN_APPROVAL).id();
        fixture.changeUpstream(runId);
        replanning.request(runId, "test-engineer");

        decide(runId, ApprovalKind.REPLAN_ACCEPTANCE, "test-approver", false);
        fixture.resume(runId);

        assertThat(event(runId)).containsEntry("STATUS", "REJECTED");
        assertThat(revision(runId)).isZero();
        assertThat(store.nodeStatuses(runId)).containsEntry("N07", NodeStatus.AWAITING_APPROVAL);
        assertThat(pending(runId, ApprovalKind.DESIGN_APPROVAL).id()).isEqualTo(design);
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("REPLAN_REJECTED");
    }

    @Test
    void anExpiredAcceptanceDiscardsTheReplanWithoutStoppingTheRun() {
        String runId = fixture.runToDesignGate();
        fixture.changeUpstream(runId);
        replanning.request(runId, "test-engineer");
        long replanApproval = pending(runId, ApprovalKind.REPLAN_ACCEPTANCE).id();
        jdbc.update("UPDATE APPROVAL SET DECISION = 'ESCALATED', TIMEOUT_AT = DATEADD('MINUTE', -20, CURRENT_TIMESTAMP),"
                + " SECOND_WINDOW_TIMEOUT_AT = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE ID = ?", replanApproval);

        engine.handleTimeouts();

        assertThat(event(runId)).containsEntry("STATUS", "EXPIRED");
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(revision(runId)).isZero();
    }

    @Test
    void aReplanNeedsAChangeANonTerminalRunAndNoPendingReplan() {
        String unchanged = fixture.runToDesignGate();
        assertThatThrownBy(() -> replanning.request(unchanged, "test-engineer")).isInstanceOf(ReplanConflictException.class)
                .hasMessageContaining("no upstream change");

        String pendingRun = fixture.runToDesignGate();
        fixture.changeUpstream(pendingRun);
        replanning.request(pendingRun, "test-engineer");
        assertThatThrownBy(() -> replanning.request(pendingRun, "test-engineer")).isInstanceOf(ReplanConflictException.class)
                .hasMessageContaining("pending");

        String terminal = fixture.runToDesignGate();
        decide(terminal, ApprovalKind.DESIGN_APPROVAL, "test-approver", false);
        fixture.resume(terminal);
        fixture.changeUpstream(terminal);
        assertThatThrownBy(() -> replanning.request(terminal, "test-engineer")).isInstanceOf(ReplanConflictException.class)
                .hasMessageContaining("terminal");
    }

    private void decide(String runId, ApprovalKind kind, String keyId, boolean approve) {
        approvals.decide(runId, pending(runId, kind).id(), keyId, KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, approve,
                approve ? "approved" : "rejected", ApprovalService.EvidenceClass.SIMULATED);
    }

    private ApprovalService.Approval pending(String runId, ApprovalKind kind) {
        return approvals.pending(runId).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    private Map<String, Object> event(String runId) {
        return jdbc.queryForMap("SELECT * FROM REPLANNING_EVENT WHERE RUN_ID = ? ORDER BY ID DESC LIMIT 1", runId);
    }

    private String invalidations(String runId) {
        return jdbc.queryForObject("SELECT CAST(INVALIDATION_RECORDS AS VARCHAR) FROM REPLANNING_EVENT WHERE RUN_ID = ? ORDER BY ID DESC LIMIT 1",
                String.class, runId);
    }

    private int revision(String runId) {
        return jdbc.queryForObject("SELECT RUN_REVISION FROM WORKFLOW_RUN WHERE ID = ?", Integer.class, runId);
    }
}
