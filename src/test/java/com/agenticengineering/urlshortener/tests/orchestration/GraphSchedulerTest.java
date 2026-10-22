package com.agenticengineering.urlshortener.tests.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.engine.EdgeKind;
import com.agenticengineering.urlshortener.orchestration.engine.GraphScheduler;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.StageTask;
import com.agenticengineering.urlshortener.orchestration.engine.StateMachines;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T052: dependency-graph execution (FR-020, FR-043, ADR-007): sequential dependencies, parallel fan-out on a bounded
 * pool, wait-for-all synchronization with failure propagation, conditional branches recorded as NOT_APPLICABLE, and
 * every transition checked against the state machine.
 */
class GraphSchedulerTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final List<String> transitions = Collections.synchronizedList(new ArrayList<>());
    private final GraphScheduler scheduler = new GraphScheduler(pool, (node, from, to, reason) -> {
        assertThat(StateMachines.isLegalNode(from, to)).as("%s %s -> %s", node, from, to).isTrue();
        transitions.add(node + ":" + from + "->" + to);
    });

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void dependenciesRunInOrder() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        StageGraph graph = StageGraph.builder().node("A").node("B").node("C")
                .edge("A", "B", EdgeKind.DEPENDENCY).edge("B", "C", EdgeKind.DEPENDENCY).build();

        GraphScheduler.RunResult result = scheduler.execute(graph, recording(order, Set.of("A", "B", "C")));

        assertThat(order).containsExactly("A", "B", "C");
        assertThat(result.statuses()).containsOnly(Map.entry("A", NodeStatus.SUCCEEDED), Map.entry("B", NodeStatus.SUCCEEDED),
                Map.entry("C", NodeStatus.SUCCEEDED));
        assertThat(transitions).contains("B:PENDING->READY", "B:READY->RUNNING", "B:RUNNING->SUCCEEDED");
    }

    @Test
    void parallelBranchesRunConcurrentlyAndTheJoinWaitsForBoth() {
        CyclicBarrier bothRunning = new CyclicBarrier(2);
        StageGraph graph = StageGraph.builder().node("A").node("B").node("C").node("D")
                .edge("A", "B", EdgeKind.DEPENDENCY).edge("A", "C", EdgeKind.DEPENDENCY)
                .edge("B", "D", EdgeKind.SYNC).edge("C", "D", EdgeKind.SYNC).build();
        StageTask meetAtBarrier = ctx -> {
            bothRunning.await(5, TimeUnit.SECONDS); // only passes if B and C run at the same time
            return StageResult.succeeded();
        };

        GraphScheduler.RunResult result = scheduler.execute(graph, Map.of("A", ctx -> StageResult.succeeded(), "B", meetAtBarrier,
                "C", meetAtBarrier, "D", ctx -> StageResult.succeeded()));

        assertThat(result.statuses().values()).containsOnly(NodeStatus.SUCCEEDED);
        assertThat(result.startedAt("B")).isBefore(result.endedAt("C"));
        assertThat(result.startedAt("C")).isBefore(result.endedAt("B"));
        Instant lastBranchEnd = result.endedAt("B").isAfter(result.endedAt("C")) ? result.endedAt("B") : result.endedAt("C");
        assertThat(result.startedAt("D")).isAfterOrEqualTo(lastBranchEnd);
    }

    @Test
    void aFailedBranchStopsTheJoinButNotTheIndependentBranch() {
        StageGraph graph = StageGraph.builder().node("A").node("B").node("C").node("D")
                .edge("A", "B", EdgeKind.DEPENDENCY).edge("A", "C", EdgeKind.DEPENDENCY)
                .edge("B", "D", EdgeKind.SYNC).edge("C", "D", EdgeKind.SYNC).build();

        GraphScheduler.RunResult result = scheduler.execute(graph, Map.of("A", ctx -> StageResult.succeeded(),
                "B", ctx -> StageResult.failed("permanent: boom"), "C", ctx -> StageResult.succeeded(),
                "D", ctx -> StageResult.succeeded()));

        assertThat(result.statuses()).containsEntry("B", NodeStatus.FAILED).containsEntry("C", NodeStatus.SUCCEEDED)
                .containsEntry("D", NodeStatus.PENDING);
        assertThat(result.failedNodes()).containsExactly("B");
        assertThat(result.unreachedNodes()).containsExactly("D");
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void anUntakenConditionalBranchIsRecordedNotApplicableAndDoesNotBlock() {
        StageGraph graph = StageGraph.builder().node("A").node("X").node("Y")
                .edge("A", "X", EdgeKind.CONDITIONAL).edge("X", "Y", EdgeKind.DEPENDENCY).edge("A", "Y", EdgeKind.DEPENDENCY).build();
        AtomicInteger xRuns = new AtomicInteger();

        GraphScheduler.RunResult result = scheduler.execute(graph, Map.of(
                "A", ctx -> StageResult.succeeded(Set.of()), // selects no conditional branch
                "X", ctx -> {
                    xRuns.incrementAndGet();
                    return StageResult.succeeded();
                },
                "Y", ctx -> StageResult.succeeded()));

        assertThat(xRuns).hasValue(0);
        assertThat(result.statuses()).containsEntry("X", NodeStatus.NOT_APPLICABLE).containsEntry("Y", NodeStatus.SUCCEEDED);
        assertThat(result.notApplicableReason("X")).contains("branch not selected by A");
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void aSelectedConditionalBranchRuns() {
        StageGraph graph = StageGraph.builder().node("A").node("X")
                .edge("A", "X", EdgeKind.CONDITIONAL).build();

        GraphScheduler.RunResult result = scheduler.execute(graph, Map.of(
                "A", ctx -> StageResult.succeeded(Set.of("X")), "X", ctx -> StageResult.succeeded()));

        assertThat(result.statuses()).containsEntry("X", NodeStatus.SUCCEEDED);
    }

    @Test
    void theThreadPoolBoundsConcurrency() {
        ExecutorService twoThreads = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            StageTask busy = ctx -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(100);
                running.decrementAndGet();
                return StageResult.succeeded();
            };
            StageGraph.Builder builder = StageGraph.builder().node("root");
            Map<String, StageTask> tasks = new ConcurrentHashMap<>(Map.of("root", ctx -> StageResult.succeeded()));
            for (int i = 0; i < 6; i++) {
                builder.node("p" + i).edge("root", "p" + i, EdgeKind.DEPENDENCY);
                tasks.put("p" + i, busy);
            }

            new GraphScheduler(twoThreads, (n, f, t, r) -> { }).execute(builder.build(), tasks);

            assertThat(maxRunning.get()).isEqualTo(2);
        } finally {
            twoThreads.shutdownNow();
        }
    }

    @Test
    void cyclesAreRejectedWhenTheGraphIsBuilt() {
        assertThatThrownBy(() -> StageGraph.builder().node("A").node("B")
                .edge("A", "B", EdgeKind.DEPENDENCY).edge("B", "A", EdgeKind.DEPENDENCY).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    private static Map<String, StageTask> recording(List<String> order, Set<String> nodes) {
        Map<String, StageTask> tasks = new ConcurrentHashMap<>();
        for (String node : nodes) {
            tasks.put(node, ctx -> {
                order.add(node);
                return StageResult.succeeded();
            });
        }
        return tasks;
    }
}
