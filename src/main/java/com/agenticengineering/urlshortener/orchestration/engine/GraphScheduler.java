package com.agenticengineering.urlshortener.orchestration.engine;

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

    private record Completion(String node, StageResult result, Instant endedAt) {
    }

    private final ExecutorService pool;
    private final TransitionListener listener;

    public GraphScheduler(ExecutorService pool, TransitionListener listener) {
        this.pool = Objects.requireNonNull(pool);
        this.listener = Objects.requireNonNull(listener);
    }

    public RunResult execute(StageGraph graph, Map<String, StageTask> tasks) {
        return execute(graph, tasks, Map.of());
    }

    /**
     * Continues from the given node states (persisted state of a resumed run); nodes not listed start PENDING. Nodes
     * already in a final or waiting state are not dispatched again.
     */
    public RunResult execute(StageGraph graph, Map<String, StageTask> tasks, Map<String, NodeStatus> initial) {
        Map<String, NodeStatus> status = new LinkedHashMap<>();
        graph.nodes().forEach(n -> status.put(n, initial.getOrDefault(n, NodeStatus.PENDING)));
        Map<String, Set<String>> selections = new HashMap<>();
        Map<String, Instant> started = new HashMap<>();
        Map<String, Instant> ended = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        List<String> failed = new ArrayList<>();
        ExecutorCompletionService<Completion> completions = new ExecutorCompletionService<>(pool);
        int running = 0;

        while (true) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String node : graph.nodes()) {
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
                        StageTask task = Objects.requireNonNull(tasks.get(node), "no executor for node " + node);
                        completions.submit(() -> {
                            StageResult result;
                            try {
                                result = task.execute(new StageContext(node));
                            } catch (Exception e) {
                                result = StageResult.failed("executor error: " + e.getClass().getSimpleName());
                            }
                            return new Completion(node, result, Instant.now());
                        });
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
            ended.put(done.node(), done.endedAt());
            if (done.result().outcome() == StageResult.Outcome.SUCCEEDED) {
                selections.put(done.node(), done.result().selectedBranches());
                transition(status, reasons, done.node(), NodeStatus.SUCCEEDED, null);
            } else if (done.result().outcome() == StageResult.Outcome.AWAITING_APPROVAL) {
                transition(status, reasons, done.node(), NodeStatus.AWAITING_APPROVAL, done.result().reason());
            } else {
                transition(status, reasons, done.node(), NodeStatus.FAILED, done.result().reason());
                failed.add(done.node());
            }
        }
        List<String> unreached = status.entrySet().stream().filter(e -> e.getValue() == NodeStatus.PENDING)
                .map(Map.Entry::getKey).toList();
        List<String> awaiting = status.entrySet().stream().filter(e -> e.getValue() == NodeStatus.AWAITING_APPROVAL)
                .map(Map.Entry::getKey).toList();
        return new RunResult(Map.copyOf(status), Map.copyOf(started), Map.copyOf(ended), Map.copyOf(reasons), List.copyOf(failed),
                unreached, awaiting);
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
