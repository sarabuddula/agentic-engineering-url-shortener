package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.ClockConfiguration;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.engine.OperationGuard;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.persistence.PolicyStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyOutcome;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** T057: every run records the evaluated policy version and one POLICY_EVALUATION row per check, audited (FR-032). */
@JdbcTest
@Import({PolicyStore.class, WorkflowStore.class, AuditChain.class, OperationGuard.class, ClockConfiguration.class})
class PolicyRecordTest {

    @Autowired
    private PolicyStore policies;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void evaluationsAreRecordedPerCheckWithTheVersionAndAudited() {
        store.createRun("run-policy-1", "req-1", "POL-2026-10-18-v1", SdlcWorkflow.graph());
        PolicyResult result = new PolicyResult("POL-2026-10-18-v1", List.of(
                new PolicyResult.Evaluation("SEC-01", PolicyOutcome.PASS, "suite:UrlValidationTest", null, "evidence verified"),
                new PolicyResult.Evaluation("DEP-01", PolicyOutcome.FAIL, "pom.xml:undeclared-lib", null, "violation"),
                new PolicyResult.Evaluation("CHG-02", PolicyOutcome.NOT_APPLICABLE, null, null, "no replan event")),
                List.of("DEP-01"));

        policies.record("run-policy-1", result);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT CHECK_ID, OUTCOME, EVIDENCE_REF FROM POLICY_EVALUATION WHERE RUN_ID = 'run-policy-1' ORDER BY ID");
        assertThat(rows).extracting(r -> r.get("CHECK_ID") + "=" + r.get("OUTCOME"))
                .containsExactly("SEC-01=PASS", "DEP-01=FAIL", "CHG-02=NOT_APPLICABLE");
        assertThat(jdbc.queryForObject("SELECT POLICY_VERSION FROM WORKFLOW_RUN WHERE ID = 'run-policy-1'", String.class))
                .isEqualTo("POL-2026-10-18-v1");
        WorkflowStore.HistoryEntry audit = store.history("run-policy-1").get(1);
        assertThat(audit.action()).isEqualTo("POLICY_EVALUATED");
        assertThat(audit.affected()).isEqualTo("POL-2026-10-18-v1");
        assertThat(audit.result()).isEqualTo("FAIL");
        assertThat(audit.reason()).contains("1 PASS, 1 FAIL, 0 EXCEPTION_REQUESTED, 1 NOT_APPLICABLE")
                .contains("mandatory failures: [DEP-01]");
    }
}
