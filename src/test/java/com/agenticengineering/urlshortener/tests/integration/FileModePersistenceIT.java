package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.UrlShortenerApplication;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T026 (reduced): the application stores both planes in an H2 file database under app.data-dir, so workflow state
 * survives a restart (FR-022, ADR-003) and the schema script re-runs safely on the existing file (PVT-006).
 */
class FileModePersistenceIT {

    @Test
    void stateSurvivesAnApplicationRestart(@TempDir Path dataDir) throws Exception {
        String[] args = {"--app.data-dir=" + dataDir, "--server.port=0"};

        try (ConfigurableApplicationContext first = start(args)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            try (Connection connection = jdbc.getDataSource().getConnection()) {
                assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:file:").contains(dataDir.toString());
            }
            jdbc.update("INSERT INTO WORKFLOW_RUN (ID, REQUIREMENT_REF, POLICY_VERSION, STATUS, RUN_REVISION, VERSION,"
                    + " AUDIT_EVENT_COUNT, CREATED_AT, EXECUTION_CLASS, REPLAN_COUNT) VALUES ('run-durable', 'req-1',"
                    + " 'POL-2026-10-18-v1', 'RUNNING', 0, 0, 0, ?, 'LIVE', 0)", Timestamp.from(Instant.now()));
        }

        try (ConfigurableApplicationContext second = start(args)) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            // the row survived the restart; since T122 boot recovery safe-stops a run found RUNNING (FR-028)
            assertThat(jdbc.queryForMap("SELECT STATUS, SAFE_STOP_CAUSE FROM WORKFLOW_RUN WHERE ID = 'run-durable'"))
                    .containsEntry("STATUS", "SAFE_STOPPED").containsEntry("SAFE_STOP_CAUSE", "BOOT_RECOVERY");
        }
    }

    private static ConfigurableApplicationContext start(String[] args) {
        return new SpringApplicationBuilder(UrlShortenerApplication.class).run(args);
    }
}
