package com.agenticengineering.urlshortener.orchestration.engine;

/** A stage executor (plan section 3): bounded work that reports a StageResult and never approves anything (FR-046). */
@FunctionalInterface
public interface StageTask {

    StageResult execute(StageContext context) throws Exception;
}
