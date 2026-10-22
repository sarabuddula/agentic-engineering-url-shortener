package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.DependencyInventoryCheck;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T082 forced-FAIL demonstration (plan section 8 "Planned demonstration"; FR-033, FR-037): an undeclared dependency is
 * injected into a copy of the build file, the real DEP-01 check evaluates it, and the run ends NOT READY: FAIL row,
 * RELEASE_AUTHORIZATION_BLOCKED, blocking reason, no release approval, N14 reporting only. The other checks use fixture
 * evidence (labelled); the control run evaluates the real pom.xml and is offered the release gate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PolicyRunFixture.class)
class DependencyPolicyDemonstrationTest {

    private static final Path INVENTORY = Path.of("docs/compliance/licenses.xml");

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path dir;

    @Test
    void anUndeclaredDependencyBlocksReleaseReadiness() throws IOException {
        Path pom = dir.resolve("pom.xml");
        String original = Files.readString(Path.of("pom.xml"));
        int at = original.indexOf("<dependencies>") + "<dependencies>".length();
        Files.writeString(pom, original.substring(0, at)
                + "<dependency><groupId>com.example</groupId><artifactId>undeclared-lib</artifactId><version>1.0.0</version></dependency>"
                + original.substring(at));
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("DEP-01", DependencyInventoryCheck.evaluate(pom, INVENTORY));

        String runId = fixture.runThroughPolicy(evidence);

        Map<String, Object> dep01 = jdbc.queryForMap("SELECT OUTCOME, EVIDENCE_REF FROM POLICY_EVALUATION WHERE RUN_ID = ? AND CHECK_ID = 'DEP-01'", runId);
        assertThat(dep01).containsEntry("OUTCOME", "FAIL");
        assertThat((String) dep01.get("EVIDENCE_REF")).contains("com.example:undeclared-lib");
        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).contains("RELEASE_AUTHORIZATION_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT RECOMMENDATION FROM RELEASE_DECISION WHERE RUN_ID = ?", String.class, runId)).isEqualTo("NOT_READY");
        assertThat(jdbc.queryForObject("SELECT BLOCKING_REASONS FROM RELEASE_DECISION WHERE RUN_ID = ?", String.class, runId))
                .contains("DEP-01").contains("com.example:undeclared-lib");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = ? AND GATE = 'G7'", Integer.class, runId)).isZero();
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.NOT_APPLICABLE).containsEntry("N14", NodeStatus.SUCCEEDED);
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.TERMINATED);
    }

    @Test
    void theRealBuildReachesTheReleaseGate() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("DEP-01", DependencyInventoryCheck.evaluate(Path.of("pom.xml"), INVENTORY));

        String runId = fixture.runThroughPolicy(evidence);

        assertThat(jdbc.queryForObject("SELECT OUTCOME FROM POLICY_EVALUATION WHERE RUN_ID = ? AND CHECK_ID = 'DEP-01'", String.class, runId))
                .isEqualTo("PASS");
        assertThat(store.nodeStatuses(runId)).containsEntry("N13", NodeStatus.AWAITING_APPROVAL);
    }
}
