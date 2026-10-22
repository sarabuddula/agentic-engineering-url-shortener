package com.agenticengineering.urlshortener.orchestration.engine;

/** Executes one decomposed task inside N08 (identified by its task key). */
@FunctionalInterface
public interface TaskExecutor {

    StageResult execute(String taskKey) throws Exception;
}
