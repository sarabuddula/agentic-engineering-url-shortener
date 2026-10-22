package com.agenticengineering.urlshortener.orchestration.engine;

/**
 * A stage executor (plan section 3 agent executor model): bounded work on whitelisted operations that reports a
 * StageResult. Executors never approve (FR-046) and never change state directly; the engine applies transitions.
 */
@FunctionalInterface
public interface StageExecutor {

    StageResult execute(RunContext context) throws Exception;

    /** A node with an alternative recorded in advance (FP-2, FR-026): it runs when the primary path fails. */
    static StageExecutor withFallback(StageExecutor primary, StageExecutor fallback) {
        return new WithFallback(primary, fallback);
    }

    /** The primary executor and the fallback the scheduler runs after a permanent failure or retry exhaustion. */
    record WithFallback(StageExecutor primary, StageExecutor fallback) implements StageExecutor {

        @Override
        public StageResult execute(RunContext context) throws Exception {
            return primary.execute(context);
        }
    }
}
