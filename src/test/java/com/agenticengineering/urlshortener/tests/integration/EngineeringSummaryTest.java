package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.stages.EngineeringSummaryStageExecutor;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T091 (FR-038): N14 writes the run's engineering summary - rationale, artifacts, risks and trade-offs, validation,
 * assumptions, limitations - from the run's own records, persists it once per run with its content hash (also as a
 * SUMMARY artifact) and links it in the decision lineage. It runs on every path, including NOT READY.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PolicyRunFixture.class)
class EngineeringSummaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private EngineeringSummaryStageExecutor summary;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private DecisionLineage lineage;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aNotReadyRunGetsACompleteSummary() throws Exception {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("DEP-01", Evidence.notOk("pom.xml: com.example:undeclared-lib not in docs/compliance/licenses.xml"));
        String runId = fixture.runThroughPolicy(evidence);

        assertThat(summary.execute(new RunContext(runId, "N14")).outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);

        String content = jdbc.queryForObject("SELECT CAST(CONTENT AS VARCHAR) FROM ENGINEERING_SUMMARY WHERE RUN_ID = ?", String.class, runId);
        JsonNode doc = JSON.readTree(content);
        for (String section : new String[] {"rationale", "artifacts", "risks_and_tradeoffs", "validation", "assumptions", "limitations"}) {
            assertThat(doc.has(section)).as(section).isTrue();
        }
        assertThat(doc.path("rationale").toString()).contains("DESIGN_APPROVAL");
        assertThat(doc.path("risks_and_tradeoffs").toString()).contains("DEP-01");
        assertThat(doc.path("validation").path("release_recommendation").asText()).isEqualTo("NOT_READY");
        assertThat(doc.path("limitations").toString()).contains("L-SC-").contains("SIMULATED");
        ArtifactStore.Artifact stored = artifacts.latest(runId, "SUMMARY").orElseThrow();
        assertThat(stored.contentHash()).isEqualTo(ArtifactStore.sha256(content));
        assertThat(lineage.forRun(runId)).anyMatch(d -> d.decision().contains("engineering summary") && d.artifactRefs().stream()
                .anyMatch(r -> r.endsWith("#" + stored.contentHash())));
    }

    @Test
    void theSummaryIsWrittenOncePerRun() throws Exception {
        String runId = fixture.runThroughPolicy(PolicyRunFixture.goodEvidence());

        summary.execute(new RunContext(runId, "N14"));
        summary.execute(new RunContext(runId, "N14"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ENGINEERING_SUMMARY WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(1);
    }
}
