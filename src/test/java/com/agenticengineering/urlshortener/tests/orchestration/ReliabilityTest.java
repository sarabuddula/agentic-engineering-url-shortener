package com.agenticengineering.urlshortener.tests.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.FailureClassifier;
import com.agenticengineering.urlshortener.orchestration.engine.GraphScheduler;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RetryPolicy;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.StageTask;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * T074 (FR-025, FR-026, PVT-004; plan section 6 FP-1/FP-2; data-model failure classification): transient failures are
 * retried at most 3 times after the initial attempt with exponential backoff and stop at the deterministic bound;
 * permanent and unknown failures are not retried; a node's recorded fallback runs when the primary path fails. Small
 * delays stand in for the production 1 s base and 60 s bound.
 */
class ReliabilityTest {

    private static final RetryPolicy FAST = new RetryPolicy(3, Duration.ofMillis(20), Duration.ofSeconds(60));
    private static final StageGraph ONE = StageGraph.builder().node("A").build();

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final List<String> transitions = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void aTransientFailureIsRetriedWithIncreasingBackoffUntilItSucceeds() {
        List<Instant> attempts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        StageTask flaky = ctx -> {
            attempts.add(Instant.now());
            return calls.incrementAndGet() < 3 ? StageResult.failed("transient: storage timeout") : StageResult.succeeded();
        };

        GraphScheduler.RunResult result = scheduler(FAST).execute(ONE, Map.of("A", flaky), Map.of(), Map.of());

        assertThat(result.statuses()).containsEntry("A", NodeStatus.SUCCEEDED);
        assertThat(calls).hasValue(3);
        assertThat(transitions).containsSubsequence("A RUNNING->FAILED", "A FAILED->RETRY_WAITING", "A RETRY_WAITING->RUNNING",
                "A RUNNING->FAILED", "A FAILED->RETRY_WAITING", "A RETRY_WAITING->RUNNING", "A RUNNING->SUCCEEDED");
        Duration first = Duration.between(attempts.get(0), attempts.get(1));
        Duration second = Duration.between(attempts.get(1), attempts.get(2));
        assertThat(first).isGreaterThanOrEqualTo(Duration.ofMillis(20));
        assertThat(second).as("exponential backoff").isGreaterThanOrEqualTo(Duration.ofMillis(40));
    }

    @Test
    void retriesAreBoundedToThreeAfterTheInitialAttempt() {
        AtomicInteger calls = new AtomicInteger();

        GraphScheduler.RunResult result = scheduler(FAST).execute(ONE,
                Map.of("A", ctx -> { calls.incrementAndGet(); return StageResult.failed("transient: storage timeout"); }), Map.of(), Map.of());

        assertThat(calls).hasValue(4);
        assertThat(result.statuses()).containsEntry("A", NodeStatus.FAILED);
        assertThat(result.reasons().get("A")).contains("retries exhausted");
    }

    @Test
    void theDeterministicBoundStopsRetryingEarly() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy tight = new RetryPolicy(3, Duration.ofMillis(40), Duration.ofMillis(70));

        GraphScheduler.RunResult result = scheduler(tight).execute(ONE,
                Map.of("A", ctx -> { calls.incrementAndGet(); return StageResult.failed("transient: storage timeout"); }), Map.of(), Map.of());

        assertThat(calls.get()).isLessThan(4);
        assertThat(result.statuses()).containsEntry("A", NodeStatus.FAILED);
        assertThat(result.reasons().get("A")).contains("deterministic bound");
    }

    @Test
    void permanentAndUnknownFailuresAreNotRetried() {
        AtomicInteger permanent = new AtomicInteger();
        AtomicInteger unknown = new AtomicInteger();
        AtomicInteger thrown = new AtomicInteger();

        scheduler(FAST).execute(ONE, Map.of("A", ctx -> { permanent.incrementAndGet(); return StageResult.failed("permanent: invalid input"); }),
                Map.of(), Map.of());
        scheduler(FAST).execute(ONE, Map.of("A", ctx -> { unknown.incrementAndGet(); return StageResult.failed("something odd"); }),
                Map.of(), Map.of());
        scheduler(FAST).execute(ONE, Map.of("A", ctx -> { thrown.incrementAndGet(); throw new IllegalStateException("bug"); }),
                Map.of(), Map.of());

        assertThat(permanent).hasValue(1);
        assertThat(unknown).as("unknown defaults to permanent").hasValue(1);
        assertThat(thrown).hasValue(1);
    }

    @Test
    void failuresAreClassifiedAsTheDataModelDefines() {
        assertThat(FailureClassifier.classify("transient: storage timeout")).isEqualTo(FailureClassifier.Kind.TRANSIENT);
        assertThat(FailureClassifier.classify("permanent: policy FAIL")).isEqualTo(FailureClassifier.Kind.PERMANENT);
        assertThat(FailureClassifier.classify("no prefix")).isEqualTo(FailureClassifier.Kind.PERMANENT);
        assertThat(FailureClassifier.classify(new QueryTimeoutException("slow"))).isEqualTo(FailureClassifier.Kind.TRANSIENT);
        assertThat(FailureClassifier.classify(new IllegalArgumentException("bad"))).isEqualTo(FailureClassifier.Kind.PERMANENT);
    }

    @Test
    void aRecordedFallbackRunsWhenThePrimaryPathFails() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        StageTask fallback = ctx -> { fallbackCalls.incrementAndGet(); return StageResult.succeeded(); };

        GraphScheduler.RunResult afterPermanent = scheduler(FAST).execute(ONE,
                Map.of("A", ctx -> StageResult.failed("permanent: primary tool unavailable")), Map.of("A", fallback), Map.of());
        GraphScheduler.RunResult afterExhaustion = scheduler(FAST).execute(ONE,
                Map.of("A", ctx -> StageResult.failed("transient: storage timeout")), Map.of("A", fallback), Map.of());

        assertThat(afterPermanent.statuses()).containsEntry("A", NodeStatus.SUCCEEDED);
        assertThat(afterExhaustion.statuses()).containsEntry("A", NodeStatus.SUCCEEDED);
        assertThat(fallbackCalls).hasValue(2);
        assertThat(transitions).anyMatch(t -> t.contains("FAILED->RETRY_WAITING") && t.contains("FP-2"));
    }

    @Test
    void aFailingFallbackLeavesTheNodeFailed() {
        GraphScheduler.RunResult result = scheduler(FAST).execute(ONE, Map.of("A", ctx -> StageResult.failed("permanent: primary")),
                Map.of("A", ctx -> StageResult.failed("permanent: fallback too")), Map.of());

        assertThat(result.statuses()).containsEntry("A", NodeStatus.FAILED);
        assertThat(result.reasons().get("A")).contains("fallback");
    }

    private GraphScheduler scheduler(RetryPolicy policy) {
        return new GraphScheduler(pool, (node, from, to, reason) -> transitions.add(node + " " + from + "->" + to
                + (reason != null ? " " + reason : "")), policy);
    }
}
