package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.shortener.domain.IdempotencyService;
import com.agenticengineering.urlshortener.shortener.domain.LinkService;
import com.agenticengineering.urlshortener.shortener.domain.RequestIdentityConflictException;
import com.agenticengineering.urlshortener.shortener.domain.Resolution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T035, PVT-003: concurrent creation against the H2 file database (FR-008 creations and duplicate submissions,
 * FR-016, NFR-003). Results must be equivalent to some serial order: no lost update, no cross-linking.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class Pvt003ConcurrencyIT {

    @Autowired
    private IdempotencyService creation;

    @Autowired
    private LinkService links;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void databaseIsTheH2File() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:file:");
        }
    }

    @Test
    void hundredConcurrentCreationsWithDistinctIdentitiesYieldHundredCorrectlyLinkedCodes() throws Exception {
        List<Callable<IdempotencyService.Outcome>> work = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int n = i;
            work.add(() -> creation.createOrReplay(String.format("key-%08d", n), TargetUrl.of("https://example.com/p/" + n), null));
        }
        List<IdempotencyService.Outcome> outcomes = runTogether(work);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            int n = i;
            IdempotencyService.Outcome outcome = outcomes.get(n);
            assertThat(outcome.created()).isTrue();
            codes.add(outcome.link().code().value());
            assertThat(links.resolve(outcome.link().code().value())).isInstanceOfSatisfying(Resolution.Redirect.class,
                    redirect -> assertThat(redirect.target().value()).isEqualTo("https://example.com/p/" + n));
        }
        assertThat(codes).hasSize(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(100);
    }

    @Test
    void concurrentRetriesWithOneIdentityLeaveExactlyOneLink() throws Exception {
        List<Callable<IdempotencyService.Outcome>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            work.add(() -> creation.createOrReplay("key-shared01", TargetUrl.of("https://example.com/same"), null));
        }
        List<IdempotencyService.Outcome> outcomes = runTogether(work);

        assertThat(outcomes.stream().map(o -> o.link().code()).distinct()).hasSize(1);
        assertThat(outcomes.stream().filter(IdempotencyService.Outcome::created)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsOnOneIdentityNeverOverwrite() throws Exception {
        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String target = i % 2 == 0 ? "https://example.com/even" : "https://example.com/odd";
            work.add(() -> {
                try {
                    return creation.createOrReplay("key-shared02", TargetUrl.of(target), null);
                } catch (RequestIdentityConflictException conflict) {
                    return conflict;
                }
            });
        }
        List<Object> results = runTogether(work);

        String stored = jdbc.queryForObject("SELECT TARGET_URL FROM SHORT_LINK WHERE REQUEST_IDENTITY = 'key-shared02'", String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(1);
        for (Object result : results) {
            if (result instanceof IdempotencyService.Outcome outcome) {
                assertThat(outcome.link().target().value()).as("a replay only ever returns the stored link").isEqualTo(stored);
            } else {
                assertThat(result).isInstanceOf(RequestIdentityConflictException.class);
            }
        }
        assertThat(results).filteredOn(RequestIdentityConflictException.class::isInstance).isNotEmpty();
    }

    /** Starts all tasks at once behind a latch so they really contend. */
    private <T> List<T> runTogether(List<Callable<T>> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : work) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
