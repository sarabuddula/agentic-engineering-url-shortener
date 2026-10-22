package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.reliability.OperationLog;
import com.agenticengineering.urlshortener.orchestration.traceability.RequirementCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * N09 real test execution (T128; FR-020, FR-030, C-004; plan section 3 whitelisted operation TS). Runs the selected
 * tests as a bounded Maven subprocess (argument list, no shell; pattern restricted to [A-Za-z0-9_*#,]), parses the
 * Surefire XML reports and records one TEST_OUTCOME per test case under a TEST_RUN operation. A failing suite fails N09;
 * no tests executed, a missing toolchain or two timeouts (180 s each, one retry; human decision at T124) are permanent
 * failures. Nothing is ever reported as passed that did not run. Maven is a runtime tool here (A-004 trust boundary).
 */
public final class TestExecutionStageExecutor implements StageExecutor {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_*#,]+");

    private record Case(String suite, String test, String status) {
    }

    private final Path workDir;
    private final String maven;
    private final Function<String, String> patternForRun;
    private final Duration timeout;
    private final JdbcTemplate jdbc;
    private final OperationLog operations;

    private TestExecutionStageExecutor(Path workDir, String maven, Function<String, String> patternForRun, Duration timeout,
            JdbcTemplate jdbc, OperationLog operations) {
        this.workDir = workDir;
        this.maven = maven;
        this.patternForRun = patternForRun;
        this.timeout = timeout;
        this.jdbc = jdbc;
        this.operations = operations;
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        String runId = context.runId();
        String pattern = patternForRun.apply(runId);
        if (pattern == null || !ALLOWED.matcher(pattern).matches()) {
            return StageResult.failed("permanent: invalid test pattern (allowed: letters, digits, _ * # ,)");
        }
        String operation = "tr:" + ArtifactStore.sha256(runId).substring(0, 16) + ":" + UUID.randomUUID().toString().substring(0, 8);
        operations.begin(runId, context.node(), "TEST_RUN", operation);
        Path reports = workDir.resolve("target/surefire-reports");
        Path log = Files.createTempFile("n09-", ".log");
        int exit = -1;
        int attempt = 0;
        boolean finished = false;
        while (!finished && attempt < 2) {
            attempt++;
            deleteReports(reports);
            Process process;
            try {
                process = new ProcessBuilder(maven, "-q", "-B", "test", "-Dtest=" + pattern, "-Dsurefire.failIfNoSpecifiedTests=false")
                        .directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            } catch (IOException e) {
                operations.fail(operation);
                return StageResult.failed("permanent: test toolchain unavailable (" + maven + "): " + e.getMessage());
            }
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (finished) {
                exit = process.exitValue();
            } else {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        }
        if (!finished) {
            operations.fail(operation);
            return StageResult.failed("permanent: test run timed out after " + timeout.toMillis() + " ms (" + attempt + " attempts)");
        }
        List<Case> cases = parse(reports);
        if (cases.isEmpty()) {
            operations.fail(operation);
            return StageResult.failed("permanent: no tests executed for pattern " + pattern + " (maven exit " + exit + ")");
        }
        Timestamp now = Timestamp.from(Instant.now());
        for (Case c : cases) {
            jdbc.update("INSERT INTO TEST_OUTCOME (RUN_ID, SUITE, TEST_ID, REQUIREMENT_REFS, STATUS, EXECUTED_AT, EVIDENCE_REF)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", runId, c.suite(), c.test(), requirementRefs(c.suite()), c.status(), now,
                    "operation:" + operation);
        }
        operations.complete(operation, true);
        List<String> failed = cases.stream().filter(c -> c.status().equals("FAILED")).map(c -> c.suite() + "#" + c.test()).toList();
        if (!failed.isEmpty()) {
            return StageResult.failed("permanent: test suite failed: " + failed.size() + " of " + cases.size() + " failed: "
                    + String.join(", ", failed.subList(0, Math.min(10, failed.size()))));
        }
        return StageResult.succeeded();
    }

    private List<Case> parse(Path reports) throws Exception {
        List<Case> cases = new ArrayList<>();
        if (!Files.isDirectory(reports)) {
            return cases;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try (Stream<Path> files = Files.list(reports)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                NodeList testcases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int i = 0; i < testcases.getLength(); i++) {
                    Element tc = (Element) testcases.item(i);
                    String status = tc.getElementsByTagName("failure").getLength() > 0 || tc.getElementsByTagName("error").getLength() > 0
                            ? "FAILED" : tc.getElementsByTagName("skipped").getLength() > 0 ? "SKIPPED" : "PASSED";
                    cases.add(new Case(tc.getAttribute("classname"), tc.getAttribute("name"), status));
                }
            }
        }
        return cases;
    }

    /** The requirement IDs the test class cites in its source, if the source is in the work directory. */
    private String requirementRefs(String className) throws IOException {
        Path source = workDir.resolve("src/test/java").resolve(className.replace('.', '/') + ".java");
        return Files.exists(source) ? String.join(",", RequirementCatalog.cited(Files.readString(source))) : null;
    }

    private static void deleteReports(Path reports) throws IOException {
        if (Files.isDirectory(reports)) {
            try (Stream<Path> files = Files.list(reports)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    /** Builds N09 executors: a fixed selection (tests) or the production one, which selects per run. */
    @Component
    public static class Factory {

        private final JdbcTemplate jdbc;
        private final OperationLog operations;
        private final ArtifactStore artifacts;
        private final String workDir;
        private final String maven;
        private final String defaultPattern;
        private final Duration timeout;
        private final ObjectMapper json = new ObjectMapper();

        public Factory(JdbcTemplate jdbc, OperationLog operations, ArtifactStore artifacts,
                @Value("${app.test-execution.working-dir:.}") String workDir, @Value("${app.test-execution.maven-command:mvn}") String maven,
                @Value("${app.test-execution.default-pattern:*Test}") String defaultPattern,
                @Value("${app.test-execution.timeout-seconds:180}") long timeoutSeconds) {
            this.jdbc = jdbc;
            this.operations = operations;
            this.artifacts = artifacts;
            this.workDir = workDir;
            this.maven = maven;
            this.defaultPattern = defaultPattern;
            this.timeout = Duration.ofSeconds(timeoutSeconds);
        }

        public TestExecutionStageExecutor create(Path workDir, String maven, String pattern, Duration timeout) {
            return new TestExecutionStageExecutor(workDir, maven, runId -> pattern, timeout, jdbc, operations);
        }

        /** Production N09: a brownfield run tests the classes its impact analysis lists; any other run the default pattern. */
        public TestExecutionStageExecutor production() {
            return new TestExecutionStageExecutor(Path.of(workDir).toAbsolutePath().normalize(), maven, this::patternFor, timeout, jdbc, operations);
        }

        private String patternFor(String runId) {
            return artifacts.latest(runId, "IMPACT_ANALYSIS").map(impact -> {
                try {
                    List<String> names = new ArrayList<>();
                    for (JsonNode test : json.readTree(impact.content()).path("tests")) {
                        String file = test.asText();
                        names.add(file.substring(file.lastIndexOf('/') + 1).replace(".java", ""));
                    }
                    return names.isEmpty() ? defaultPattern : String.join(",", names);
                } catch (IOException e) {
                    return defaultPattern;
                }
            }).orElse(defaultPattern);
        }
    }
}
