package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/** T010 walking skeleton (FR-010, plan section 14 slice 2): both planes boot and health reports UP, in-process and as the fat jar. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalkingSkeletonIT {

    private static final Duration BOOT_TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthIsUpInProcess() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void fatJarBootsAndReportsHealthUp() throws Exception {
        Path jar = findFatJar();
        int port = freePort();
        Path log = Files.createTempFile("walking-skeleton", ".log");
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(List.of(javaBin, "-jar", jar.toString(), "--server.port=" + port))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            HttpResponse<String> health = awaitHealth(port, process);

            assertThat(health.statusCode()).as("health status code; jar log at %s", log).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"");
        } finally {
            process.destroy();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    private static Path findFatJar() throws IOException {
        Path target = Paths.get("target");
        assertThat(target).as("build output directory").isDirectory();
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(p -> p.getFileName().toString().matches("url-shortener-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no fat jar in " + target.toAbsolutePath()
                            + "; run mvn verify so the package phase repackages it"));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HttpResponse<String> awaitHealth(int port, Process process) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5))
                .build();
        Instant deadline = Instant.now().plus(BOOT_TIMEOUT);
        IOException last = null;
        while (Instant.now().isBefore(deadline)) {
            assertThat(process.isAlive()).as("jar process exited early with code %s", exitCode(process)).isTrue();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException notYetListening) {
                last = notYetListening;
                Thread.sleep(500);
            }
        }
        throw new AssertionError("jar did not answer /actuator/health within " + BOOT_TIMEOUT, last);
    }

    private static String exitCode(Process process) {
        return process.isAlive() ? "n/a" : String.valueOf(process.exitValue());
    }
}
