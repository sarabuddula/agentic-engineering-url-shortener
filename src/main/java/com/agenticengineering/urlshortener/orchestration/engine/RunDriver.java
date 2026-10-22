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

    /** Starts a newly created run off the request thread: it proceeds to its first gate or outcome. */
    public void startAsync(String runId) {
        runner.submit(() -> {
            try {
                engine.execute(runId, registry.executors(), registry.taskExecutor());
            } catch (RuntimeException e) {
                LOG.error("start failed run_id={} type={}", runId, e.getClass().getName());
            }
        });
    }

    /** Continues an authorized resumption off the request thread (T110). */
    public void continueAsync(String runId) {
        runner.submit(() -> {
            try {
                engine.continueRun(runId, registry.executors(), registry.taskExecutor());
            } catch (RuntimeException e) {
                LOG.error("continue failed run_id={} type={}", runId, e.getClass().getName());
            }
        });
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        runner.shutdownNow();
    }
}
