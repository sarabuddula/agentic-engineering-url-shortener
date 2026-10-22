package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An explicit, acyclic stage graph (FR-020, FR-036): nodes plus typed edges, validated when built. */
public final class StageGraph {

    public record Edge(String from, String to, EdgeKind kind) {
    }

    private final Set<String> nodes;
    private final List<Edge> edges;
    private final Map<String, List<Edge>> incoming;

    private StageGraph(Set<String> nodes, List<Edge> edges) {
        this.nodes = Collections.unmodifiableSet(nodes);
        this.edges = List.copyOf(edges);
        Map<String, List<Edge>> in = new LinkedHashMap<>();
        nodes.forEach(n -> in.put(n, new ArrayList<>()));
        edges.forEach(e -> in.get(e.to()).add(e));
        this.incoming = in;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> nodes() {
        return nodes;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> incoming(String node) {
        return Collections.unmodifiableList(incoming.get(node));
    }

    public static final class Builder {
        private final Set<String> nodes = new LinkedHashSet<>();
        private final List<Edge> edges = new ArrayList<>();

        public Builder node(String key) {
            if (!nodes.add(key)) {
                throw new IllegalArgumentException("duplicate node " + key);
            }
            return this;
        }

        public Builder edge(String from, String to, EdgeKind kind) {
            edges.add(new Edge(from, to, kind));
            return this;
        }

        public StageGraph build() {
            for (Edge e : edges) {
                if (!nodes.contains(e.from()) || !nodes.contains(e.to())) {
                    throw new IllegalArgumentException("edge references an unknown node: " + e);
                }
                if (e.from().equals(e.to())) {
                    throw new IllegalArgumentException("self edge is a cycle: " + e);
                }
            }
            // Kahn's algorithm: every node must be reachable in topological order
            Map<String, Integer> inDegree = new HashMap<>();
            nodes.forEach(n -> inDegree.put(n, 0));
            edges.forEach(e -> inDegree.merge(e.to(), 1, Integer::sum));
            Deque<String> ready = new ArrayDeque<>();
            inDegree.forEach((n, d) -> {
                if (d == 0) {
                    ready.add(n);
                }
            });
            int visited = 0;
            while (!ready.isEmpty()) {
                String n = ready.poll();
                visited++;
                for (Edge e : edges) {
                    if (e.from().equals(n) && inDegree.merge(e.to(), -1, Integer::sum) == 0) {
                        ready.add(e.to());
                    }
                }
            }
            if (visited != nodes.size()) {
                throw new IllegalArgumentException("stage graph contains a cycle");
            }
            return new StageGraph(new LinkedHashSet<>(nodes), edges);
        }
    }
}
