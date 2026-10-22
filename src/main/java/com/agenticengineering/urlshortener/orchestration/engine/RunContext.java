package com.agenticengineering.urlshortener.orchestration.engine;

/** What a stage executor receives: the run and the node it executes. */
public record RunContext(String runId, String node) {
}
