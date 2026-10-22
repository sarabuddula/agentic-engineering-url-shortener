package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T086: once a run reaches a terminal state the engine re-verifies the audit chain, including the terminal appends,
 * and appends the result as the run's last event before reporting the outcome (FR-048, ADR-005).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PolicyRunFixture.class, CompletionEvidenceFixture.class})
class TerminalVerificationTest {

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private CompletionEvidenceFixture completionEvidence;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private AuditChain chain;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCompletedRunEndsWithAChainVerifiedEvent() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runThroughPolicy(evidence);
        completionEvidence.recordAll(runId); // FR-044 (T101): labelled fixture evidence so the run may complete
        fixture.decideRelease(runId, evidence, true);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.COMPLETED);
        assertEndsVerified(runId);
    }

    @Test
    void aNotReadyRunEndsWithAChainVerifiedEvent() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("DEP-01", Evidence.notOk("fixture: undeclared dependency"));

        String runId = fixture.runThroughPolicy(evidence);

        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
        assertEndsVerified(runId);
    }

    @Test
    void tamperingBeforeTheEndIsReportedInTheLastEvent() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runThroughPolicy(evidence);
        jdbc.update("UPDATE AUDIT_EVENT SET REASON = 'rewritten' WHERE RUN_ID = ? AND SEQ = 2", runId);

        // a rejected release ends the run (TERMINATED); a tampered chain could never pass the completion gate (T101)
        fixture.decideRelease(runId, evidence, false);

        WorkflowStore.HistoryEntry last = last(runId);
        assertThat(last.action()).isEqualTo("CHAIN_VERIFICATION_FAILED");
        assertThat(last.reason()).contains("position 2").contains("HASH_MISMATCH");
    }

    private void assertEndsVerified(String runId) {
        List<WorkflowStore.HistoryEntry> history = store.history(runId);
        WorkflowStore.HistoryEntry last = history.get(history.size() - 1);
        assertThat(last.action()).isEqualTo("CHAIN_VERIFIED");
        assertThat(last.reason()).contains((history.size() - 1) + " events");
        assertThat(history.get(history.size() - 2).action()).as("verification follows the terminal transition").isEqualTo("RUN_TRANSITION");
        assertThat(chain.verify(runId).intact()).isTrue();
    }

    private WorkflowStore.HistoryEntry last(String runId) {
        List<WorkflowStore.HistoryEntry> history = store.history(runId);
        return history.get(history.size() - 1);
    }
}
