package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T020: hash-chained run audit (FR-048, NFR-006, plan section 4). Appends advance the chain head; verification reports
 * the position and check for each tamper class: altered, removed, reordered, truncated.
 */
@JdbcTest
@Import(AuditChain.class)
class AuditChainTest {

    private static final Instant T0 = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private AuditChain chain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void seedRun() {
        seedRun("run-1");
    }

    @Test
    void appendAdvancesTheHeadAndLinksToThePredecessor() {
        AuditChain.Appended first = chain.append("run-1", entry("RUN_CREATED", 0));
        AuditChain.Appended second = chain.append("run-1", entry("NODE_STARTED", 1));

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.seq()).isEqualTo(2);
        assertThat(first.eventHash()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT PREV_HASH FROM AUDIT_EVENT WHERE RUN_ID = 'run-1' AND SEQ = 1", String.class))
                .isEqualTo(AuditChain.GENESIS_HASH);
        assertThat(jdbc.queryForObject("SELECT PREV_HASH FROM AUDIT_EVENT WHERE RUN_ID = 'run-1' AND SEQ = 2", String.class))
                .isEqualTo(first.eventHash());
        assertThat(jdbc.queryForMap("SELECT AUDIT_HEAD_HASH, AUDIT_EVENT_COUNT FROM WORKFLOW_RUN WHERE ID = 'run-1'"))
                .containsEntry("AUDIT_HEAD_HASH", second.eventHash())
                .containsEntry("AUDIT_EVENT_COUNT", 2L);
    }

    @Test
    void anUntouchedChainVerifies() {
        appendThree();

        assertThat(chain.verify("run-1")).isEqualTo(ChainVerification.intact(3));
    }

    @Test
    void alteredEventIsDetectedAtItsPosition() {
        appendThree();
        jdbc.update("UPDATE AUDIT_EVENT SET REASON = 'rewritten' WHERE RUN_ID = 'run-1' AND SEQ = 2");

        assertThat(chain.verify("run-1")).isEqualTo(ChainVerification.broken(2, ChainVerification.Check.HASH_MISMATCH));
    }

    @Test
    void removedEventIsDetectedAtItsPosition() {
        appendThree();
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE RUN_ID = 'run-1' AND SEQ = 2");

        assertThat(chain.verify("run-1")).isEqualTo(ChainVerification.broken(2, ChainVerification.Check.SEQUENCE_GAP));
    }

    @Test
    void reorderedEventsAreDetectedAtTheFirstMovedPosition() {
        appendThree();
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 99 WHERE RUN_ID = 'run-1' AND SEQ = 2");
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 2 WHERE RUN_ID = 'run-1' AND SEQ = 3");
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 3 WHERE RUN_ID = 'run-1' AND SEQ = 99");

        assertThat(chain.verify("run-1")).isEqualTo(ChainVerification.broken(2, ChainVerification.Check.PREDECESSOR_MISMATCH));
    }

    @Test
    void truncatedTailIsDetectedAgainstTheRetainedCount() {
        appendThree();
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE RUN_ID = 'run-1' AND SEQ = 3");

        assertThat(chain.verify("run-1")).isEqualTo(ChainVerification.broken(3, ChainVerification.Check.TRUNCATION));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void appendCommitsOrRollsBackWithTheCallersStateChange() {
        String runId = "run-atomic";
        seedRun(runId);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactions);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE WORKFLOW_RUN SET STATUS = 'AWAITING_APPROVAL' WHERE ID = ?", runId);
                chain.append(runId, entry("STATUS_CHANGED", 0));
                throw new IllegalStateException("caller fails after the append");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE RUN_ID = ?", Integer.class, runId)).isZero();
            assertThat(jdbc.queryForMap("SELECT STATUS, AUDIT_EVENT_COUNT FROM WORKFLOW_RUN WHERE ID = ?", runId))
                    .containsEntry("STATUS", "RUNNING").containsEntry("AUDIT_EVENT_COUNT", 0L);
        } finally {
            cleanUp(runId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAppendsProduceOneContiguousChain() throws Exception {
        String runId = "run-parallel";
        seedRun(runId);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                int t = thread;
                work.add(() -> {
                    for (int i = 0; i < 10; i++) {
                        chain.append(runId, entry("PARALLEL_STEP_" + t, i));
                    }
                    return null;
                });
            }
            for (Future<Void> done : pool.invokeAll(work)) {
                done.get();
            }

            assertThat(chain.verify(runId)).isEqualTo(ChainVerification.intact(80));
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT PREV_HASH) FROM AUDIT_EVENT WHERE RUN_ID = ?", Integer.class, runId))
                    .as("no two events share a predecessor, so there are no competing heads").isEqualTo(80);
        } finally {
            pool.shutdownNow();
            cleanUp(runId);
        }
    }

    private void appendThree() {
        chain.append("run-1", entry("RUN_CREATED", 0));
        chain.append("run-1", entry("NODE_STARTED", 1));
        chain.append("run-1", entry("NODE_SUCCEEDED", 2));
    }

    private static AuditEntry entry(String action, int secondsAfterT0) {
        return new AuditEntry(AuditEntry.ActorType.SYSTEM, action, T0.plusSeconds(secondsAfterT0), "N01", "OK", "test " + action);
    }

    private void seedRun(String runId) {
        jdbc.update("INSERT INTO WORKFLOW_RUN (ID, REQUIREMENT_REF, POLICY_VERSION, STATUS, RUN_REVISION, VERSION,"
                + " AUDIT_EVENT_COUNT, CREATED_AT, EXECUTION_CLASS, REPLAN_COUNT) VALUES (?, 'req-1', 'POL-2026-10-18-v1',"
                + " 'RUNNING', 0, 0, 0, ?, 'LIVE', 0)", runId, Timestamp.from(T0));
    }

    private void cleanUp(String runId) {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE RUN_ID = ?", runId);
        jdbc.update("DELETE FROM WORKFLOW_RUN WHERE ID = ?", runId);
    }
}
