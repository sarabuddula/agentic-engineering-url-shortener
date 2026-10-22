package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Defect found in the SCN-C REPLAY demonstration (2026-10-22, run-05c9fd8d...): N08 tasks t3 and t5 ran in parallel and
 * both recorded "CODE v3" - ArtifactStore read MAX(VERSION) + 1 and inserted it without serializing concurrent writers
 * of the same run and type. Concurrent records of different content must get distinct, gap-free versions.
 * Requirements: FR-023, FR-030.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ArtifactVersionConcurrencyTest {

    private static final int WRITERS = 8;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentRecordsOfOneTypeGetDistinctVersions() throws Exception {
        String runId = engine.createRun("REQ-ARTIFACT-RACE", "POL-2026-10-18-v1");
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writes = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            String content = "{\"task\":\"t" + i + "\"}";
            writes.add(pool.submit(() -> {
                start.await();
                return artifacts.record(runId, "CODE", content, "AI_ASSISTED_HUMAN_REVIEWED", "N08");
            }));
        }
        start.countDown();
        for (Future<?> write : writes) {
            write.get();
        }
        pool.shutdown();

        List<Integer> versions = jdbc.queryForList("SELECT VERSION FROM ARTIFACT WHERE RUN_ID = ? AND TYPE = 'CODE' ORDER BY VERSION",
                Integer.class, runId);
        assertThat(versions).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }
}
