package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Per-node executor whitelist (plan section 3, amendment 2026-10-19; amended and enforced 2026-10-22 by human decision,
 * approvals/2026-10-22-chk047-whitelist.md). While the engine runs a stage executor it records the run and node here;
 * the guarded operations check this list. Operations: AR versioned artifact read, AW versioned artifact write, TS
 * test-suite invocation, MQ metrics query, PE policy evaluation, AQ approval-request creation.
 */
public final class OperationWhitelist {

    public enum Op { AR, AW, TS, MQ, PE, AQ }

    /** The run and node whose executor is running on this thread. */
    public record Context(String runId, String node) {
    }

    private static final Map<String, Set<Op>> LIST = Map.ofEntries(
            Map.entry("N01", EnumSet.of(Op.AW)),
            Map.entry("N02", EnumSet.of(Op.AR, Op.AW)),
            Map.entry("N03", EnumSet.of(Op.AR, Op.AQ)),
            Map.entry("N04", EnumSet.of(Op.AR, Op.AW, Op.AQ)),
            Map.entry("N05", EnumSet.of(Op.AR, Op.AW)),
            Map.entry("N06", EnumSet.of(Op.AR, Op.AW)),
            Map.entry("N07", EnumSet.of(Op.AR, Op.AQ)),
            Map.entry("N08", EnumSet.of(Op.AR, Op.AW)),
            Map.entry("N09", EnumSet.of(Op.AR, Op.TS)),
            Map.entry("N10", EnumSet.of(Op.AR, Op.AW)),
            Map.entry("N11", EnumSet.of(Op.AR, Op.AW, Op.MQ)),
            Map.entry("N12", EnumSet.of(Op.AR, Op.PE, Op.TS)),
            Map.entry("N13", EnumSet.of(Op.AR, Op.AQ)),
            Map.entry("N14", EnumSet.of(Op.AR, Op.AW)));

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private OperationWhitelist() {
    }

    public static Set<Op> allowed(String node) {
        return Set.copyOf(LIST.getOrDefault(node, EnumSet.noneOf(Op.class)));
    }

    public static Optional<Context> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Runs the work as the node's executor; the context is cleared afterwards (pool threads are reused). */
    public static <T> T within(String runId, String node, Callable<T> work) throws Exception {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(runId, node));
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
