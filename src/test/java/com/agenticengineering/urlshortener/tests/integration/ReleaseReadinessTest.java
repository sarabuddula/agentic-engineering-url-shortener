package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T080: release readiness (FR-037, ADR-009). N12 records exactly one of READY, READY_WITH_ACCEPTED_LIMITATIONS or
 * NOT_READY with decision PENDING; the system only recommends. The human decision is the G7 approval by a distinct
 * RELEASE_OWNER, settled into RELEASE_DECISION (approver, time, evidence reference) and the run's terminal outcome.
 * Requirements: US-004 (release-readiness determination).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PolicyRunFixture.class, CompletionEvidenceFixture.class})
class ReleaseReadinessTest {

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private CompletionEvidenceFixture completionEvidence;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void passingChecksRecommendReadyAndOnlyTheHumanDecides() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runThroughPolicy(evidence);

        assertThat(decision(runId)).containsEntry("RECOMMENDATION", "READY").containsEntry("DECISION", "PENDING");
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.AWAITING_APPROVAL);
        completionEvidence.recordAll(runId); // FR-044 (T101): labelled fixture evidence so the run may complete

        long approvalId = fixture.decideRelease(runId, evidence, true);

        Map<String, Object> decided = decision(runId);
        assertThat(decided).containsEntry("DECISION", "APPROVED").containsEntry("APPROVER_KEY_ID", "test-release-owner")
                .containsEntry("EVIDENCE_REF", "approval:" + approvalId);
        assertThat(decided.get("DECIDED_AT")).isNotNull();
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
        assertThat(terminalOutcome(runId)).isEqualTo("READY");
    }

    @Test
    void anApprovedLimitationRecommendsReadyWithAcceptedLimitations() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runToDesignGate(evidence);
        acceptLimitation(runId, "analytics loss window up to 60 s on crash");

        fixture.passDesignGate(runId, evidence);

        assertThat(decision(runId)).containsEntry("RECOMMENDATION", "READY_WITH_ACCEPTED_LIMITATIONS").containsEntry("DECISION", "PENDING");
    }

    @Test
    void aRejectedReleaseIsRecordedAndEndsTheRun() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runThroughPolicy(evidence);

        fixture.decideRelease(runId, evidence, false);

        assertThat(decision(runId)).containsEntry("RECOMMENDATION", "READY").containsEntry("DECISION", "REJECTED")
                .containsEntry("APPROVER_KEY_ID", "test-release-owner");
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertThat(terminalOutcome(runId)).isEqualTo("RELEASE_REJECTED");
    }

    @Test
    void aMandatoryFailureRecommendsNotReady() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("SEC-01", Evidence.notOk("gitleaks: 1 finding"));

        String runId = fixture.runThroughPolicy(evidence);

        assertThat(decision(runId)).containsEntry("RECOMMENDATION", "NOT_READY").containsEntry("DECISION", "PENDING");
        assertThat((String) decision(runId).get("BLOCKING_REASONS")).contains("SEC-01");
    }

    /** G6 approval by a distinct RELEASE_OWNER (SIMULATED) and its ACCEPTED_LIMITATION row; T093 builds this path for runs. */
    private void acceptLimitation(String runId, String text) {
        ApprovalService.Approval g6 = approvals.request(runId, ApprovalKind.ACCEPTED_LIMITATION, "N12", "test-engineer", text);
        approvals.decide(runId, g6.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, AuditEntry.ActorType.HUMAN, true,
                "accepted limitation: " + text, ApprovalService.EvidenceClass.SIMULATED);
        jdbc.update("INSERT INTO ACCEPTED_LIMITATION (RUN_ID, DESCRIPTION, APPROVAL_ID, APPROVED_AT) VALUES (?, ?, ?, ?)", runId, text,
                g6.id(), Timestamp.from(Instant.now()));
    }

    private Map<String, Object> decision(String runId) {
        return jdbc.queryForMap("SELECT * FROM RELEASE_DECISION WHERE RUN_ID = ?", runId);
    }

    private String terminalOutcome(String runId) {
        return jdbc.queryForObject("SELECT TERMINAL_OUTCOME FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
    }
}
