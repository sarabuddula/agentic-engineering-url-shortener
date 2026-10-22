package com.agenticengineering.urlshortener.orchestration.engine;

/**
 * A stage executor (plan section 3 agent executor model): bounded work on whitelisted operations that reports a
 * StageResult. Executors never approve (FR-046) and never change state directly; the engine applies transitions.
 */
@FunctionalInterface
public interface StageExecutor {

    StageResult execute(RunContext context) throws Exception;
}
