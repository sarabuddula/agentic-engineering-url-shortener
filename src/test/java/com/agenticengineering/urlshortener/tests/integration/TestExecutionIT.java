package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.stages.TestExecutionStageExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T128 (FR-020, FR-030, C-004, Constitution IV): N09 runs a real test suite as a bounded Maven subprocess and records
 * the actual results - one TEST_OUTCOME per test case under a TEST_RUN operation - so a failing suite fails N09 and a
 * timeout or missing toolchain is a classified failure, never a fabricated pass. The suite is a fixture project with one
 * passing and one failing test, copied to a temporary directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestExecutionIT {

    @Autowired
    private TestExecutionStageExecutor.Factory executors;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path project;

    @BeforeEach
    void copyFixtureProject() throws IOException {
        Path source = Path.of("src/test/resources/fixtures/test-project");
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                Path target = project.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
    }

    @Test
    void aFailingSuiteFailsN09WithTheRealCounts() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        StageResult result = executors.create(project, "mvn", "*Test", Duration.ofSeconds(180)).execute(new RunContext(runId, "N09"));

        assertThat(result.outcome()).isEqualTo(StageResult.Outcome.FAILED);
        assertThat(result.reason()).startsWith("permanent:").contains("1 of 2 failed").contains("FailingTest");
        assertThat(statuses(runId)).containsEntry("fixture.PassingTest#adds", "PASSED").containsEntry("fixture.FailingTest#subtractsWrongly", "FAILED");
        assertThat(jdbc.queryForObject("SELECT REQUIREMENT_REFS FROM TEST_OUTCOME WHERE RUN_ID = ? AND TEST_ID LIKE '%PassingTest%'",
                String.class, runId)).isEqualTo("FR-001");
        assertThat(jdbc.queryForMap("SELECT TYPE, STATUS, EFFECT_VERIFIED FROM OPERATION_RECORD WHERE RUN_ID = ? AND TYPE = 'TEST_RUN'", runId))
                .containsEntry("STATUS", "COMPLETED").containsEntry("EFFECT_VERIFIED", true);
    }

    @Test
    void aPassingSelectionSucceeds() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        StageResult result = executors.create(project, "mvn", "PassingTest", Duration.ofSeconds(180)).execute(new RunContext(runId, "N09"));

        assertThat(result.outcome()).isEqualTo(StageResult.Outcome.SUCCEEDED);
        assertThat(statuses(runId)).containsOnly(Map.entry("fixture.PassingTest#adds", "PASSED"));
    }

    @Test
    void aMissingToolchainOrABadPatternIsAPermanentFailure() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        StageResult missing = executors.create(project, "no-such-maven", "*Test", Duration.ofSeconds(180)).execute(new RunContext(runId, "N09"));
        StageResult injected = executors.create(project, "mvn", "*Test; rm -rf /", Duration.ofSeconds(180)).execute(new RunContext(runId, "N09"));

        assertThat(missing.outcome()).isEqualTo(StageResult.Outcome.FAILED);
        assertThat(missing.reason()).startsWith("permanent:").contains("toolchain");
        assertThat(injected.reason()).startsWith("permanent:").contains("pattern");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ?", Integer.class, runId)).isZero();
    }

    @Test
    void aTimeoutIsRetriedOnceThenFailsPermanently() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        StageResult result = executors.create(project, "mvn", "*Test", Duration.ofMillis(200)).execute(new RunContext(runId, "N09"));

        assertThat(result.reason()).startsWith("permanent:").contains("timed out").contains("2 attempts");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ?", Integer.class, runId)).isZero();
    }

    private Map<String, String> statuses(String runId) {
        Map<String, String> statuses = new java.util.TreeMap<>();
        jdbc.query("SELECT SUITE, TEST_ID, STATUS FROM TEST_OUTCOME WHERE RUN_ID = ?",
                rs -> { statuses.put(rs.getString("SUITE") + "#" + rs.getString("TEST_ID"), rs.getString("STATUS")); }, runId);
        return statuses;
    }
}
