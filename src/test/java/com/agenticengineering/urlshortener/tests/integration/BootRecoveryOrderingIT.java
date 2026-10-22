package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.UrlShortenerApplication;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Defect found while running the demonstrations (2026-10-22; KillRestartIT failed once in a full build): boot recovery
 * ran on ApplicationReadyEvent, after the web server already answered requests, so for a moment after a restart an
 * interrupted run was reported RUNNING and could not be resumed. Boot recovery must finish before the web server
 * accepts requests. Requirements: FR-028, PVT-006.
 */
class BootRecoveryOrderingIT {

    @Test
    void anInterruptedRunIsSafeStoppedBeforeTheWebServerAcceptsRequests() throws Exception {
        Path data = Files.createTempDirectory("boot-recovery-ordering");
        String runId;
        try (ConfigurableApplicationContext first = new SpringApplicationBuilder(UrlShortenerApplication.class)
                .web(WebApplicationType.NONE).run("--app.data-dir=" + data)) {
            runId = first.getBean(OrchestrationEngine.class).createRun("api:test-engineer", "POL-2026-10-18-v1");
            WorkflowStore store = first.getBean(WorkflowStore.class);
            store.transitionNode(runId, "N01", NodeStatus.PENDING, NodeStatus.READY, "fixture");
            store.transitionNode(runId, "N01", NodeStatus.READY, NodeStatus.RUNNING, "fixture: the JVM stopped here");
        }

        AtomicReference<String> statusWhenServing = new AtomicReference<>();
        ApplicationListener<WebServerInitializedEvent> probe = event -> statusWhenServing.set(event.getApplicationContext()
                .getBean(JdbcTemplate.class).queryForObject("SELECT STATUS FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId));
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(UrlShortenerApplication.class).listeners(probe)
                .run("--app.data-dir=" + data, "--server.port=0")) {
            assertThat(statusWhenServing.get()).isEqualTo("SAFE_STOPPED");
        }
    }
}
