package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T019: control-plane schema (data-model.md "Control plane") - every entity present and the invariants the data model
 * names enforced by the database (FR-020, FR-022, FR-036, FR-046, FR-048). Re-running the whole schema script is
 * covered by ApplicationSchemaTest: DDL commits implicitly in H2, so it must not run after rows are seeded here.
 */
@JdbcTest
class ControlPlaneSchemaTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-21T12:00:00Z"));
    private static final String HASH = "b".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRun() {
        jdbc.update("INSERT INTO WORKFLOW_RUN (ID, REQUIREMENT_REF, POLICY_VERSION, STATUS, RUN_REVISION, VERSION,"
                + " AUDIT_EVENT_COUNT, CREATED_AT, EXECUTION_CLASS, REPLAN_COUNT) VALUES ('run-1', 'req-1', 'POL-2026-10-18-v1',"
                + " 'RUNNING', 0, 0, 0, ?, 'LIVE', 0)", NOW);
    }

    @Test
    void everyControlPlaneEntityHasATable() {
        List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class);

        assertThat(tables).contains("WORKFLOW_RUN", "REQUIREMENT_ARTIFACT", "ARTIFACT", "STAGE_NODE", "STAGE_EDGE",
                "NODE_INPUT", "TASK", "TASK_DEPENDENCY", "OPERATION_RECORD", "FAILURE_EVENT", "APPROVAL",
                "ACCEPTED_LIMITATION", "RELEASE_DECISION", "AUDIT_EVENT", "PLATFORM_AUDIT_EVENT", "PLATFORM_AUDIT_HEAD",
                "POLICY_EVALUATION", "POLICY_EXCEPTION", "REPLANNING_EVENT", "DECISION_LINEAGE", "TEST_OUTCOME",
                "RISK_OUTCOME", "ENGINEERING_SUMMARY", "KEY_IDENTITY");
    }

    @Test
    void auditSequenceIsUniquePerRun() {
        insertAudit("run-1", 1);

        assertThatThrownBy(() -> insertAudit("run-1", 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void platformAuditHasItsOwnUniqueSequence() {
        jdbc.update("INSERT INTO PLATFORM_AUDIT_EVENT (SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, RESULT, PREV_HASH, EVENT_HASH)"
                + " VALUES (1, 'SYSTEM', 'RATE_LIMITED', ?, 'REJECTED', ?, ?)", NOW, HASH, HASH);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO PLATFORM_AUDIT_EVENT (SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, RESULT,"
                + " PREV_HASH, EVENT_HASH) VALUES (1, 'SYSTEM', 'AUTH_FAILED', ?, 'REJECTED', ?, ?)", NOW, HASH, HASH))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void taskDependenciesAreExplicitRowsWithACompositeKey() {
        long node = insertNode("N08");
        long a = insertTask(node, "task a");
        long b = insertTask(node, "task b");
        jdbc.update("INSERT INTO TASK_DEPENDENCY (TASK_ID, DEPENDS_ON_TASK_ID) VALUES (?, ?)", b, a);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO TASK_DEPENDENCY (TASK_ID, DEPENDS_ON_TASK_ID) VALUES (?, ?)", b, a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'TASK_DEPENDENCY'",
                String.class)).containsExactlyInAnyOrder("TASK_ID", "DEPENDS_ON_TASK_ID");
    }

    @Test
    void enumeratedColumnsRejectValuesOutsideTheDataModel() {
        long from = insertNode("N09");
        long to = insertNode("N11");
        jdbc.update("INSERT INTO STAGE_EDGE (RUN_ID, FROM_NODE, TO_NODE, KIND) VALUES ('run-1', ?, ?, 'SYNC')", from, to);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO STAGE_EDGE (RUN_ID, FROM_NODE, TO_NODE, KIND)"
                + " VALUES ('run-1', ?, ?, 'LOOP')", from, to)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO POLICY_EVALUATION (RUN_ID, CHECK_ID, OUTCOME)"
                + " VALUES ('run-1', 'DEP-01', 'MAYBE')")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO APPROVAL (RUN_ID, GATE, REQUESTER_KEY_ID, DECISION, EVIDENCE_CLASS)"
                + " VALUES ('run-1', 'G9', 'demo-engineer', 'PENDING', 'HUMAN')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void approverMustDifferFromRequester() {
        jdbc.update("INSERT INTO APPROVAL (RUN_ID, GATE, REQUESTER_KEY_ID, APPROVER_KEY_ID, DECISION, EVIDENCE_CLASS)"
                + " VALUES ('run-1', 'G2', 'demo-engineer', 'demo-approver', 'APPROVED', 'HUMAN')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO APPROVAL (RUN_ID, GATE, REQUESTER_KEY_ID, APPROVER_KEY_ID, DECISION,"
                + " EVIDENCE_CLASS) VALUES ('run-1', 'G2', 'demo-approver', 'demo-approver', 'APPROVED', 'HUMAN')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRunHasAtMostOneReleaseDecision() {
        jdbc.update("INSERT INTO RELEASE_DECISION (RUN_ID, RECOMMENDATION, DECISION) VALUES ('run-1', 'NOT_READY', 'PENDING')");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO RELEASE_DECISION (RUN_ID, RECOMMENDATION, DECISION)"
                + " VALUES ('run-1', 'READY', 'PENDING')")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runScopedRowsMustReferenceAnExistingRun() {
        assertThatThrownBy(() -> insertAudit("no-such-run", 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertAudit(String runId, long seq) {
        jdbc.update("INSERT INTO AUDIT_EVENT (RUN_ID, SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, RESULT, PREV_HASH, EVENT_HASH)"
                + " VALUES (?, ?, 'SYSTEM', 'RUN_CREATED', ?, 'OK', ?, ?)", runId, seq, NOW, HASH, HASH);
    }

    private long insertNode(String nodeKey) {
        jdbc.update("INSERT INTO STAGE_NODE (RUN_ID, NODE_KEY, STATUS, ATTEMPT_COUNT, RUN_REVISION, VERSION)"
                + " VALUES ('run-1', ?, 'PENDING', 0, 0, 0)", nodeKey);
        return jdbc.queryForObject("SELECT MAX(ID) FROM STAGE_NODE", Long.class);
    }

    private long insertTask(long nodeId, String title) {
        jdbc.update("INSERT INTO TASK (RUN_ID, NODE_ID, TITLE, STATUS, EXECUTOR_CLASS)"
                + " VALUES ('run-1', ?, ?, 'PENDING', 'DETERMINISTIC')", nodeId, title);
        return jdbc.queryForObject("SELECT MAX(ID) FROM TASK", Long.class);
    }
}
