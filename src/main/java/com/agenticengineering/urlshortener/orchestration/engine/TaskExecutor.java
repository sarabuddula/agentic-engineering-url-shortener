package com.agenticengineering.urlshortener.orchestration.engine;

/** Executes one decomposed task inside N08 (identified by its task key). */
@FunctionalInterface
public interface TaskExecutor {

    StageResult execute(String taskKey) throws Exception;

    /** The engine calls this form; executors that need the run (implementation evidence) override it. */
    default StageResult execute(String runId, String taskKey) throws Exception {
        return execute(taskKey);
    }
}
