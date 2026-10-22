package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FR-044 completion gate (T101; Constitution XI). A run may be declared COMPLETED only when every condition is evidenced
 * by the run's own records:
 * <ol>
 * <li>requirements identified: a REQUIREMENT_ARTIFACT row;</li>
 * <li>decisions recorded: the design decision (G2, N07) in DECISION_LINEAGE; the release decision alone does not count;</li>
 * <li>acceptance criteria met: test outcomes linked to requirements exist and all passed;</li>
 * <li>tests executed: test outcomes exist and none failed;</li>
 * <li>security and reliability checked: SEC-01..SEC-03 passed (latest evaluation) and no unrecovered failure;</li>
 * <li>documentation current: a DOC artifact;</li>
 * <li>traceability complete: CHG-03 (bidirectional traceability) passed;</li>
 * <li>limitations disclosed: an engineering summary with a limitations section;</li>
 * <li>approval recorded: an approved G7 release authorization;</li>
 * <li>evidence available: the run's audit chain verifies.</li>
 * </ol>
 */
@Component
public class CompletionGate {

    public enum Condition {
        REQUIREMENTS_IDENTIFIED, DECISIONS_RECORDED, ACCEPTANCE_CRITERIA_MET, TESTS_EXECUTED, SECURITY_AND_RELIABILITY_CHECKED,
        DOCUMENTATION_CURRENT, TRACEABILITY_COMPLETE, LIMITATIONS_DISCLOSED, APPROVAL_RECORDED, EVIDENCE_AVAILABLE
    }

    private final JdbcTemplate jdbc;
    private final AuditChain chain;

    public CompletionGate(JdbcTemplate jdbc, AuditChain chain) {
        this.jdbc = jdbc;
        this.chain = chain;
    }

    /** The conditions without evidence, in Constitution XI order; empty when the run may complete. */
    public List<Condition> unmet(String runId) {
        List<Condition> unmet = new ArrayList<>();
        check(unmet, Condition.REQUIREMENTS_IDENTIFIED, count("SELECT COUNT(*) FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?", runId) > 0);
        check(unmet, Condition.DECISIONS_RECORDED,
                count("SELECT COUNT(*) FROM DECISION_LINEAGE WHERE RUN_ID = ? AND DECISION LIKE '%DESIGN_APPROVAL%'", runId) > 0);
        String linked = "REQUIREMENT_REFS IS NOT NULL AND REQUIREMENT_REFS <> ''";
        check(unmet, Condition.ACCEPTANCE_CRITERIA_MET,
                count("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND " + linked, runId) > 0
                        && count("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND " + linked + " AND STATUS <> 'PASSED'", runId) == 0);
        check(unmet, Condition.TESTS_EXECUTED, count("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ?", runId) > 0
                && count("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND STATUS <> 'PASSED'", runId) == 0);
        check(unmet, Condition.SECURITY_AND_RELIABILITY_CHECKED, passed(runId, "SEC-01") && passed(runId, "SEC-02") && passed(runId, "SEC-03")
                && count("SELECT COUNT(*) FROM FAILURE_EVENT WHERE RUN_ID = ? AND NOT RECOVERED AND NOT MTTR_EXCLUDED", runId) == 0);
        check(unmet, Condition.DOCUMENTATION_CURRENT, count("SELECT COUNT(*) FROM ARTIFACT WHERE RUN_ID = ? AND TYPE = 'DOC'", runId) > 0);
        check(unmet, Condition.TRACEABILITY_COMPLETE, passed(runId, "CHG-03"));
        check(unmet, Condition.LIMITATIONS_DISCLOSED, count("SELECT COUNT(*) FROM ENGINEERING_SUMMARY WHERE RUN_ID = ?"
                + " AND LOWER(CAST(CONTENT AS VARCHAR)) LIKE '%limitations%'", runId) > 0);
        check(unmet, Condition.APPROVAL_RECORDED, count("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = ?"
                + " AND (KIND = 'RELEASE_AUTHORIZATION' OR GATE = 'G7') AND DECISION = 'APPROVED'", runId) > 0);
        check(unmet, Condition.EVIDENCE_AVAILABLE, chain.verify(runId).intact());
        return unmet;
    }

    private boolean passed(String runId, String check) {
        List<String> latest = jdbc.queryForList("SELECT OUTCOME FROM POLICY_EVALUATION WHERE RUN_ID = ? AND CHECK_ID = ? ORDER BY ID DESC LIMIT 1",
                String.class, runId, check);
        return !latest.isEmpty() && latest.get(0).equals("PASS");
    }

    private int count(String sql, String runId) {
        return jdbc.queryForObject(sql, Integer.class, runId);
    }

    private static void check(List<Condition> unmet, Condition condition, boolean evidenced) {
        if (!evidenced) {
            unmet.add(condition);
        }
    }
}
