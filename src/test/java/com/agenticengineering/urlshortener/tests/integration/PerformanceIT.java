package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T045: PVT-001 (at least 99% resolution success for valid active links) and PVT-002 (at least 95% of valid requests
 * resolved within 200 ms), local demonstration environment (NFR-007). Labels follow spec.md; the T045 task text has
 * the two labels swapped. Demonstration measurement, not a production statistic (C-004): one sequential local client,
 * client-side latency over loopback including the synchronous analytics write. The per-IP redirect limit is raised for
 * this test only, because the PVTs measure resolution, not the limiter. Report: target/pvt/pvt-001-002.json.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.redirect-per-ip.requests-per-minute=1000000", "app.rate-limit.redirect-per-ip.burst=100000"})
class PerformanceIT {

    private static final int LINKS = 50;
    private static final int WARM_UP = 100;
    private static final int MEASURED = 1000;
    private static final long LATENCY_TARGET_MS = 200;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM REDIRECT_EVENT");
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void redirectSuccessAndLatencyMeetPvt001AndPvt002() throws Exception {
        for (int i = 0; i < LINKS; i++) {
            Instant expiresAt = Instant.now().plus(Duration.ofDays(1));
            jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", String.format("perf%04d", i), "https://example.com/p/" + i,
                    String.format("key-perf-%04d", i), "2".repeat(64), Timestamp.from(expiresAt.minus(Duration.ofDays(7))),
                    Timestamp.from(expiresAt));
        }
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (int i = 0; i < WARM_UP; i++) {
            get(client, i % LINKS);
        }

        List<Long> latenciesMicros = new ArrayList<>();
        int successes = 0;
        for (int i = 0; i < MEASURED; i++) {
            long start = System.nanoTime();
            HttpResponse<Void> response = get(client, i % LINKS);
            latenciesMicros.add((System.nanoTime() - start) / 1_000);
            if (response.statusCode() == 302
                    && ("https://example.com/p/" + (i % LINKS)).equals(response.headers().firstValue("Location").orElse(null))) {
                successes++;
            }
        }

        Collections.sort(latenciesMicros);
        long withinTarget = latenciesMicros.stream().filter(us -> us < LATENCY_TARGET_MS * 1_000).count();
        double successRate = successes / (double) MEASURED;
        double withinTargetRate = withinTarget / (double) MEASURED;
        writeReport(successRate, withinTargetRate, latenciesMicros);

        assertThat(successRate).as("PVT-001 success rate").isGreaterThanOrEqualTo(0.99);
        assertThat(withinTargetRate).as("PVT-002 share within 200 ms").isGreaterThanOrEqualTo(0.95);
    }

    private HttpResponse<Void> get(HttpClient client, int link) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + String.format("perf%04d", link)))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static void writeReport(double successRate, double withinTargetRate, List<Long> sortedMicros) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("measurement_class", "DEMONSTRATION");
        report.put("measured_at", Instant.now().toString());
        report.put("pvts", List.of("PVT-001", "PVT-002"));
        Map<String, Object> workload = new LinkedHashMap<>();
        workload.put("population_valid_active_links", LINKS);
        workload.put("warm_up_requests_excluded", WARM_UP);
        workload.put("measured_requests", MEASURED);
        workload.put("client", "one sequential java.net.http client over loopback; latency measured client-side");
        report.put("workload", workload);
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("pvt_001_success_rate", successRate);
        results.put("pvt_002_share_within_200_ms", withinTargetRate);
        results.put("latency_ms_p50", sortedMicros.get(sortedMicros.size() / 2) / 1000.0);
        results.put("latency_ms_p95", sortedMicros.get((int) (sortedMicros.size() * 0.95) - 1) / 1000.0);
        results.put("latency_ms_p99", sortedMicros.get((int) (sortedMicros.size() * 0.99) - 1) / 1000.0);
        results.put("latency_ms_max", sortedMicros.get(sortedMicros.size() - 1) / 1000.0);
        report.put("results", results);
        report.put("environment", environment());
        Path out = Path.of("target/pvt/pvt-001-002.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
    }

    private static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        env.put("available_processors", Runtime.getRuntime().availableProcessors());
        env.put("jvm_max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            env.put("physical_memory_mb", os.getTotalMemorySize() / (1024 * 1024));
        }
        env.put("jdk", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        env.put("database", "H2 file mode, same JVM");
        return env;
    }
}
