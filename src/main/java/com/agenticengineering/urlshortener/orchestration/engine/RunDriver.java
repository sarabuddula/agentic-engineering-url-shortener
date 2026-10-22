package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Continues runs after human decisions off the request thread, one at a time, with the production executors. */
@Component
public class RunDriver {

    private static final Logger LOG = LoggerFactory.getLogger(RunDriver.class);

    private final OrchestrationEngine engine;
    private final StageExecutorRegistry registry;
    private final ExecutorService runner = Executors.newSingleThreadExecutor();

    public RunDriver(OrchestrationEngine engine, StageExecutorRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    public void resumeAsync(String runId) {
        runner.submit(() -> {
            try {
                engine.resume(runId, registry.executors(), registry.taskExecutor());
            } catch (RuntimeException e) {
                LOG.error("resume failed run_id={} type={}", runId, e.getClass().getName());
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        runner.shutdownNow();
    }
}
