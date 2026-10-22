package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fifth governed change (2026-10-21, T066 a-b): approvals persist their kind, kinds without a gate can be stored, and a
 * policy exception references its G5 approval directly.
 * Requirements: FR-024 (approval kinds), FR-033 (policy exceptions).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApprovalSchemaAdditionsTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private PolicyExceptionService exceptions;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anApprovalRecordsItsKind() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs");

        assertThat(jdbc.queryForObject("SELECT KIND FROM APPROVAL WHERE ID = ?", String.class, approval.id())).isEqualTo("DESIGN_APPROVAL");
    }

    @Test
    void aKindWithoutAGateCanBeStored() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        int inserted = jdbc.update("INSERT INTO APPROVAL (RUN_ID, KIND, GATE, REQUESTER_KEY_ID, DECISION, TIMEOUT_AT, EVIDENCE_CLASS)"
                + " VALUES (?, 'REPLAN_ACCEPTANCE', NULL, 'test-engineer', 'PENDING', ?, 'HUMAN')", runId, Timestamp.from(Instant.now()));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void aPolicyExceptionReferencesItsApproval() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        PolicyExceptionService.PolicyException requested = exceptions.request(runId, "DEP-01", "reason", "scope", "test-engineer");

        assertThat(jdbc.queryForObject("SELECT APPROVAL_ID FROM POLICY_EXCEPTION WHERE ID = ?", Long.class, requested.id()))
                .isEqualTo(requested.approvalId());
    }
}
