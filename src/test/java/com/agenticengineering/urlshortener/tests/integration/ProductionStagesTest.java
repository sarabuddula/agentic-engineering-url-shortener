package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.stages.DecompositionStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.DesignStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.DocumentationStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.EvidenceStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.ImplementationTaskExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.QualityStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.RequirementIngestion;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Production executors for N06 design, N08 implementation tasks, N10 documentation and N11 evidence assembly (needed by
 * the scenario runs T087-T089; plan section 3 stage table and executor whitelist). Design, documentation and evidence
 * are deterministic artifacts built from the run's own records; an implementation task succeeds only when the workspace
 * holds a commit marked "[run {id} task {key}]" - code is never fabricated - and otherwise fails so the run safe-stops
 * until the engineer commits and resumes it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProductionStagesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private RequirementIngestion ingestion;

    @Autowired
    private QualityStageExecutor quality;

    @Autowired
    private DecompositionStageExecutor decomposition;

    @Autowired
    private DesignStageExecutor design;

    @Autowired
    private ImplementationTaskExecutor.Factory implementation;

    @Autowired
    private DocumentationStageExecutor.Factory documentation;

    @Autowired
    private EvidenceStageExecutor evidence;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path repo;

    @Test
    void theDesignArtifactIsBuiltFromTheRunsRecords() throws Exception {
        String runId = analysed(ScenarioFixtures.scnA());

        assertThat(design.execute(new RunContext(runId, "N06")).outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);

        JsonNode doc = JSON.readTree(artifacts.latest(runId, "DESIGN").orElseThrow().content());
        assertThat(doc.path("requirement").asText()).contains("Link status lookup");
        assertThat(doc.path("tasks")).isNotEmpty();
        assertThat(doc.path("acceptance_criteria")).isNotEmpty();
        assertThat(doc.path("api_and_schema_impacts").toString()).contains("greenfield");
    }

    @Test
    void anImplementationTaskNeedsItsMarkedCommit() throws Exception {
        String runId = analysed(ScenarioFixtures.scnA());
        initRepo();
        ImplementationTaskExecutor tasks = implementation.forWorkspace(repo);

        StageResult missing = tasks.execute(runId, "t1");
        Files.writeString(repo.resolve("LinkStatus.java"), "class LinkStatus {}\n");
        git("add", ".");
        git("commit", "-q", "-m", "feat: link status [run " + runId + " task t1]");
        StageResult found = tasks.execute(runId, "t1");

        assertThat(missing.outcome()).isEqualTo(StageResult.Outcome.FAILED);
        assertThat(missing.reason()).startsWith("permanent:").contains("[run " + runId + " task t1]");
        assertThat(found.outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);
        assertThat(artifacts.latest(runId, "CODE").orElseThrow().content()).contains(git("rev-parse", "HEAD")).contains("LinkStatus.java");
    }

    @Test
    void documentationListsTheChangesAndEvidenceNeedsExecutedTests() throws Exception {
        String runId = analysed(ScenarioFixtures.scnA());
        initRepo();
        Files.writeString(repo.resolve("README.md"), "# docs\n");
        git("add", ".");
        git("commit", "-q", "-m", "docs: link status [run " + runId + " task t1]");
        implementation.forWorkspace(repo).execute(runId, "t1");

        StageResult noTests = evidence.execute(new RunContext(runId, "N11"));
        assertThat(documentation.forWorkspace(repo).execute(new RunContext(runId, "N10")).outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);
        jdbc.update("INSERT INTO TEST_OUTCOME (RUN_ID, SUITE, TEST_ID, REQUIREMENT_REFS, STATUS, EXECUTED_AT, EVIDENCE_REF)"
                + " VALUES (?, 'x.LinkStatusTest', 'works', 'FR-001', 'PASSED', ?, 'operation:tr-1')", runId, Timestamp.from(Instant.now()));
        StageResult withTests = evidence.execute(new RunContext(runId, "N11"));

        assertThat(artifacts.latest(runId, "DOC").orElseThrow().content()).contains("README.md");
        assertThat(noTests.reason()).startsWith("permanent:").contains("no test outcomes");
        assertThat(withTests.outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);
        assertThat(artifacts.latest(runId, "EVIDENCE").orElseThrow().content()).contains("\"passed\":1");
    }

    /** N01, N02 and N05 production executors on the requirement. */
    private String analysed(String requirement) throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ingestion.ingest(runId, RequirementIngestion.normalize(requirement));
        quality.execute(new RunContext(runId, "N02"));
        decomposition.execute(new RunContext(runId, "N05"));
        return runId;
    }

    private void initRepo() throws Exception {
        git("init", "-q");
        Files.writeString(repo.resolve("base.txt"), "base\n");
        git("add", ".");
        git("commit", "-q", "-m", "base");
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "-c", "user.name=fixture", "-c",
                "user.email=fixture@localhost", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
