package com.agenticengineering.urlshortener.orchestration.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

/**
 * Executes a StageGraph (T052; FR-020, FR-043, ADR-007). Nodes whose predecessors are all satisfied (SUCCEEDED or
 * NOT_APPLICABLE) are dispatched together to the bounded pool, so independent branches run in parallel; a SYNC join
 * waits for every branch. A failed predecessor leaves its dependents PENDING (failure propagates along that path
 * only; independent branches continue). A CONDITIONAL target runs only if a predecessor selects it, else it is
 * NOT_APPLICABLE with the reason recorded. All state changes happen on the calling thread, checked against
 * StateMachines and reported to the TransitionListener in order.
 *
 * <p>Failures (T075; FR-025, FR-026): a transient failure is retried (FAILED -> RETRY_WAITING -> RUNNING) with
 * exponential backoff, at most {@link RetryPolicy#maxRetries()} times and never past the deterministic bound; permanent
 * and unknown failures are not retried. When the primary path fails for good, the node's recorded fallback runs once
 * (FP-2); if there is none, or it fails too, the node stays FAILED.
 */
public class GraphScheduler {

    /** Final state of one execution. */
    public record RunResult(Map<String, NodeStatus> statuses, Map<String, Instant> started, Map<String, Instant> ended,
            Map<String, String> reasons, List<String> failedNodes, List<String> unreachedNodes, List<String> awaitingNodes) {

        public Instant startedAt(String node) {
            return started.get(node);
        }

        public Instant endedAt(String node) {
            return ended.get(node);
        }

        public String notApplicableReason(String node) {
            return statuses.get(node) == NodeStatus.NOT_APPLICABLE ? reasons.get(node) : null;
        }

        /** Every node finished: no failure, nothing waiting for a decision, nothing left pending. */
        public boolean succeeded() {
            return failedNodes.isEmpty() && unreachedNodes.isEmpty() && awaitingNodes.isEmpty();
        }
    }

    /** A finished attempt, or (retryDue) the end of a backoff wait. */
    private record Completion(String node, StageResult result, Instant endedAt, boolean retryDue) {
    }

    /** Failure handling state of one node in this execution. */
    private static final class Attempts {
        Instant firstStart;
        int retries;
        boolean onFallback;
        String primaryFailure;
    }

    private final ExecutorService pool;
    private final TransitionListener listener;
    private final RetryPolicy policy;

    public GraphScheduler(ExecutorService pool, TransitionListener listener) {
        this(pool, listener, RetryPolicy.DEFAULT);
    }

    public GraphScheduler(ExecutorService pool, TransitionListener listener, RetryPolicy policy) {
        this.pool = Objects.requireNonNull(pool);
        this.listener = Objects.requireNonNull(listener);
        this.policy = Objects.requireNonNull(policy);
    }

    public RunResult execute(StageGraph graph, Map<String, StageTask> tasks) {
        return execute(graph, tasks, Map.of(), Map.of());
    }

    public RunResult execute(StageGraph graph, Map<String, StageTask> tasks, Map<String, NodeStatus> initial) {
        return execute(graph, tasks, Map.of(), initial);
    }

    /**
     * Continues from the given node states (persisted state of a resumed run); nodes not listed start PENDING. Nodes
     * already in a final or waiting state are not dispatched again.
     */
    public RunResult execute(StageGraph graph, Map<String, StageTask> tasks, Map<String, StageTask> fallbacks,
            Map<String, NodeStatus> initial) {
        Map<String, NodeStatus> status = new LinkedHashMap<>();
        graph.nodes().forEach(n -> status.put(n, initial.getOrDefault(n, NodeStatus.PENDING)));
        Map<String, Set<String>> selections = new HashMap<>();
        Map<String, Instant> started = new HashMap<>();
        Map<String, Instant> ended = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        List<String> failed = new ArrayList<>();
        Map<String, Attempts> attempts = new HashMap<>();
        ExecutorCompletionService<Completion> completions = new ExecutorCompletionService<>(pool);
        int running = 0;

        while (true) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String node : graph.nodes()) {
                    if (status.get(node) == NodeStatus.READY) {
                        // a node returned to READY by a governed resumption (T110): its entry criteria held when it stopped
                        transition(status, reasons, node, NodeStatus.RUNNING, "resumed");
                        started.put(node, Instant.now());
                        attempts.computeIfAbsent(node, n -> new Attempts()).firstStart = Instant.now();
                        submit(completions, node, Objects.requireNonNull(tasks.get(node), "no executor for node " + node));
                        running++;
                        changed = true;
                        continue;
                    }
                    if (status.get(node) != NodeStatus.PENDING) {
                        continue;
                    }
                    Readiness readiness = readiness(graph, node, status, selections);
                    if (readiness == Readiness.NOT_APPLICABLE) {
                        String reason = "branch not selected by " + conditionalSources(graph, node);
                        transition(status, reasons, node, NodeStatus.NOT_APPLICABLE, reason);
                        changed = true;
                    } else if (readiness == Readiness.READY) {
                        transition(status, reasons, node, NodeStatus.READY, null);
                        transition(status, reasons, node, NodeStatus.RUNNING, null);
                        started.put(node, Instant.now());
                        attempts.computeIfAbsent(node, n -> new Attempts()).firstStart = Instant.now();
                        submit(completions, node, Objects.requireNonNull(tasks.get(node), "no executor for node " + node));
                        running++;
                        changed = true;
                    }
                }
            }
            if (running == 0) {
                break;
            }
            Completion done = take(completions);
            running--;
            String node = done.node();
            if (done.retryDue()) {
                Attempts a = attempts.get(node);
                transition(status, reasons, node, NodeStatus.RUNNING, a.onFallback ? "FP-2 fallback executing"
                        : "FP-1 retry " + a.retries + " of " + policy.maxRetries());
                submit(completions, node, a.onFallback ? fallbacks.get(node) : tasks.get(node));
                running++;
                continue;
            }
            ended.put(node, done.endedAt());
            if (done.result().outcome() == StageResult.Outcome.FAILED) {
                transition(status, reasons, node, NodeStatus.FAILED, done.result().reason());
                if (recover(completions, node, done.result().reason(), attempts.get(node), fallbacks, status, reasons)) {
                    running++;
                } else {
                    failed.add(node);
                }
                continue;
            }
            if (done.result().outcome() == StageResult.Outcome.SUCCEEDED) {
                selections.put(done.node(), done.result().selectedBranches());
                transition(status, reasons, done.node(), NodeStatus.SUCCEEDED, null);
            } else if (done.result().outcome() == StageResult.Outcome.AWAITING_APPROVAL) {
                transition(status, reasons, done.node(), NodeStatus.AWAITING_APPROVAL, done.result().reason());
            }
        }
        List<String> unreached = status.entrySet().stream().filter(e -> e.getValue() == NodeStatus.PENDING)
                .map(Map.Entry::getKey).toList();
        List<String> awaiting = status.entrySet().stream().filter(e -> e.getValue() == NodeStatus.AWAITING_APPROVAL)
                .map(Map.Entry::getKey).toList();
        return new RunResult(Map.copyOf(status), Map.copyOf(started), Map.copyOf(ended), Map.copyOf(reasons), List.copyOf(failed),
                unreached, awaiting);
    }

    /**
     * After a FAILED transition: schedules a retry (transient, within bounds) or the fallback and returns true, or
     * records why the node stays failed and returns false.
     */
    private boolean recover(ExecutorCompletionService<Completion> completions, String node, String reason, Attempts a,
            Map<String, StageTask> fallbacks, Map<String, NodeStatus> status, Map<String, String> reasons) {
        String finalReason;
        if (a.onFallback) {
            finalReason = "fallback failed: " + reason + " (primary: " + a.primaryFailure + ")";
        } else if (FailureClassifier.classify(reason) == FailureClassifier.Kind.TRANSIENT) {
            if (a.retries < policy.maxRetries()) {
                Duration delay = policy.delay(a.retries + 1);
                if (Duration.between(a.firstStart, Instant.now()).plus(delay).compareTo(policy.deadline()) <= 0) {
                    a.retries++;
                    transition(status, reasons, node, NodeStatus.RETRY_WAITING, "FP-1 retry " + a.retries + " of "
                            + policy.maxRetries() + " after " + delay.toMillis() + " ms: " + reason);
                    wake(completions, node, delay);
                    return true;
                }
                finalReason = "transient failure; deterministic bound of " + policy.deadline().toSeconds() + " s reached after "
                        + (a.retries + 1) + " attempts: " + reason;
            } else {
                finalReason = "transient failure; retries exhausted (" + a.retries + " of " + policy.maxRetries() + "): " + reason;
            }
        } else {
            finalReason = reason;
        }
        if (!a.onFallback && fallbacks.containsKey(node)) {
            a.onFallback = true;
            a.primaryFailure = finalReason;
            transition(status, reasons, node, NodeStatus.RETRY_WAITING, "FP-2 fallback after primary failure: " + finalReason);
            wake(completions, node, Duration.ZERO);
            return true;
        }
        reasons.put(node, finalReason);
        return false;
    }

    private static void submit(ExecutorCompletionService<Completion> completions, String node, StageTask task) {
        completions.submit(() -> {
            StageResult result;
            try {
                result = task.execute(new StageContext(node));
            } catch (Exception e) {
                String kind = FailureClassifier.classify(e) == FailureClassifier.Kind.TRANSIENT ? "transient" : "permanent";
                result = StageResult.failed(kind + ": executor error: " + e.getClass().getSimpleName());
            }
            return new Completion(node, result, Instant.now(), false);
        });
    }

    /** Completes after the delay, on a pool thread, so the backoff never blocks the scheduling thread. */
    private static void wake(ExecutorCompletionService<Completion> completions, String node, Duration delay) {
        completions.submit(() -> {
            Thread.sleep(delay.toMillis());
            return new Completion(node, null, Instant.now(), true);
        });
    }

    private enum Readiness { WAITING, READY, NOT_APPLICABLE }

    private static Readiness readiness(StageGraph graph, String node, Map<String, NodeStatus> status,
            Map<String, Set<String>> selections) {
        boolean anyConditional = false;
        boolean selected = false;
        for (StageGraph.Edge edge : graph.incoming(node)) {
            NodeStatus source = status.get(edge.from());
            if (source != NodeStatus.SUCCEEDED && source != NodeStatus.NOT_APPLICABLE) {
                return Readiness.WAITING; // still running, pending, failed or stopped: this path does not continue
            }
            if (edge.kind() == EdgeKind.CONDITIONAL) {
                anyConditional = true;
                selected |= source == NodeStatus.SUCCEEDED && selections.getOrDefault(edge.from(), Set.of()).contains(node);
            }
        }
        if (anyConditional && !selected) {
            return Readiness.NOT_APPLICABLE;
        }
        return Readiness.READY;
    }

    private static String conditionalSources(StageGraph graph, String node) {
        return String.join(", ", graph.incoming(node).stream().filter(e -> e.kind() == EdgeKind.CONDITIONAL)
                .map(StageGraph.Edge::from).toList());
    }

    private void transition(Map<String, NodeStatus> status, Map<String, String> reasons, String node, NodeStatus to, String reason) {
        NodeStatus from = status.get(node);
        StateMachines.checkNode(from, to);
        status.put(node, to);
        if (reason != null) {
            reasons.put(node, reason);
        }
        listener.onTransition(node, from, to, reason);
    }

    private static Completion take(ExecutorCompletionService<Completion> completions) {
        try {
            return completions.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("scheduler interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("stage completion failed", e.getCause());
        }
    }
}
