package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.reliability.CompensationService;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureEvidence;
import com.agenticengineering.urlshortener.orchestration.reliability.IrreversibleOperationException;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T123 (FR-027, FR-030, NFR-005): failure events, failure audit entries and compensation-related records are written in
 * their own transaction (REQUIRES_NEW), so rolling back the operation they describe never erases them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FailureRecordingTransactionTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private FailureEvidence failures;

    @Autowired
    private CompensationService compensation;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private AuditChain chain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void aRolledBackOperationKeepsItsFailureEventAndAuditEntry() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ARTIFACT (RUN_ID, TYPE, VERSION, CONTENT_HASH, PROVENANCE, CREATED_AT)"
                    + " VALUES (?, 'DESIGN', 1, ?, 'DETERMINISTIC', ?)", runId, "0".repeat(64), Timestamp.from(Instant.now()));
            failures.operationFailed(runId, "N06", "design write failed: constraint violation");
            throw new IllegalStateException("operation fails and rolls back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ARTIFACT WHERE RUN_ID = ?", Integer.class, runId)).as("rolled back").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FAILURE_EVENT WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("OPERATION_FAILED") && e.reason().contains("design write failed"));
        assertThat(chain.verify(runId).intact()).isTrue();
    }

    @Test
    void aRejectedRollbackStaysAuditedWhenTheCallerRollsBack() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                compensation.rollback(runId, "N08", "commit 0123456"))).isInstanceOf(IrreversibleOperationException.class);

        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("ROLLBACK_REJECTED"));
        assertThat(chain.verify(runId).intact()).isTrue();
    }
}
