package com.agenticengineering.urlshortener.tests.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T078 (PVT-006, NFR-008, FR-028): kill-and-restart of the real application jar. The first JVM is halted by fault
 * injection right after an artifact is written and before its operation is marked complete (the
 * after-effect/before-completion-record case). On restart, boot recovery verifies the effect (OPERATION_RECONCILED
 * APPLIED) and safe-stops the run (BOOT_RECOVERY); resumption needs an APPROVER; the resumed run continues without
 * writing the artifact again. Oracle: exactly one effect per operation - one IMPACT_ANALYSIS artifact.
 */
class KillRestartIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void aRunKilledAfterAnEffectResumesWithoutDuplicatingIt() throws Exception {
        Path data = Files.createTempDirectory("kill-restart-data");
        int port = freePort();

        Process first = start(data, port, "--app.fault-injection.halt-after-effect=ARTIFACT_WRITE");
        String runId;
        try {
            awaitHealth(port, first);
            runId = JSON.readTree(post(port, "/api/v1/orchestrator/runs", "ENGINEER",
                    JSON.writeValueAsString(Map.of("requirement_text", ScenarioFixtures.scnB()))).body()).path("run_id").asText();
            assertThat(first.waitFor(60, TimeUnit.SECONDS)).as("the JVM halts after the artifact write").isTrue();
        } finally {
            first.destroyForcibly();
        }

        Process second = start(data, port);
        try {
            awaitHealth(port, second);
            JsonNode detail = JSON.readTree(get(port, "/api/v1/orchestrator/runs/" + runId).body());
            assertThat(detail.path("status").asText()).isEqualTo("SAFE_STOPPED");
            List<JsonNode> history = history(port, runId);
            assertThat(history).anyMatch(e -> e.path("action").asText().equals("OPERATION_RECONCILED") && e.path("result").asText().equals("APPLIED"));
            assertThat(history).anyMatch(e -> e.path("action").asText().equals("BOOT_RECOVERY"));

            assertThat(post(port, "/api/v1/orchestrator/runs/" + runId + "/resume", "ENGINEER", "").statusCode()).as("governed stop").isEqualTo(403);
            assertThat(post(port, "/api/v1/orchestrator/runs/" + runId + "/resume", "APPROVER", "").statusCode()).isEqualTo(200);
            String status = "RUNNING";
            for (int i = 0; i < 100 && status.equals("RUNNING"); i++) {
                Thread.sleep(200);
                status = JSON.readTree(get(port, "/api/v1/orchestrator/runs/" + runId).body()).path("status").asText();
            }
            assertThat(status).as("the resumed run reaches the change approval at N04").isEqualTo("AWAITING_APPROVAL");
            long impactWrites = history(port, runId).stream().filter(e -> e.path("action").asText().equals("ARTIFACT_RECORDED")
                    && e.path("reason").asText().startsWith("IMPACT_ANALYSIS")).count();
            assertThat(impactWrites).as("exactly one effect per operation").isEqualTo(1);
        } finally {
            second.destroy();
            if (!second.waitFor(20, TimeUnit.SECONDS)) {
                second.destroyForcibly();
            }
        }
    }

    private static Process start(Path data, int port, String... extra) throws IOException {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaBin, "-jar", fatJar().toString(), "--server.port=" + port, "--app.data-dir=" + data,
                "--app.security.key-file=" + Path.of("src/test/resources/fixtures/demo-keys.test.yml").toAbsolutePath()));
        command.addAll(List.of(extra));
        Path log = Files.createTempFile("kill-restart", ".log");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static List<JsonNode> history(int port, String runId) throws Exception {
        List<JsonNode> entries = new ArrayList<>();
        JSON.readTree(get(port, "/api/v1/orchestrator/runs/" + runId + "/history").body()).forEach(entries::add);
        return entries;
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, String role, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitHealth(int port, Process process) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            assertThat(process.isAlive()).as("application exited early").isTrue();
            try {
                if (HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notYet) {
                // not listening yet
            }
            Thread.sleep(500);
        }
        throw new AssertionError("application did not become healthy");
    }

    private static Path fatJar() throws IOException {
        try (Stream<Path> files = Files.list(Paths.get("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("url-shortener-.*\\.jar")).findFirst()
                    .orElseThrow(() -> new AssertionError("no fat jar; run mvn verify"));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
