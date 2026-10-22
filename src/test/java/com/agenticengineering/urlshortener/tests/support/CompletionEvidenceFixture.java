package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.orchestration.engine.CompletionGate;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Records labelled FIXTURE evidence for each Constitution XI condition the completion gate (FR-044) checks, except the
 * ones a test omits. Approval (condition 9) is never fabricated here: it comes from a real G7 decision in the test, and
 * evidence availability (condition 10) from the run's own audit chain. Tests bring it in with @Import.
 */
public class CompletionEvidenceFixture {

    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final RequirementStore requirements;

    public CompletionEvidenceFixture(JdbcTemplate jdbc, ArtifactStore artifacts, RequirementStore requirements) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.requirements = requirements;
    }

    public void recordAll(String runId) {
        recordExcept(runId, EnumSet.noneOf(CompletionGate.Condition.class));
    }

    public void recordExcept(String runId, Set<CompletionGate.Condition> omitted) {
        Timestamp now = Timestamp.from(Instant.now());
        if (!omitted.contains(CompletionGate.Condition.REQUIREMENTS_IDENTIFIED)) {
            requirements.saveIngested(runId, "FIXTURE requirement: link status lookup", RequirementStore.Provenance.CONFIRMED);
        }
        if (!omitted.contains(CompletionGate.Condition.DECISIONS_RECORDED)) {
            jdbc.update("INSERT INTO DECISION_LINEAGE (RUN_ID, DECISION, RATIONALE) VALUES (?, 'FIXTURE DESIGN_APPROVAL decision',"
                    + " 'FIXTURE rationale')", runId);
        }
        if (!omitted.contains(CompletionGate.Condition.ACCEPTANCE_CRITERIA_MET)) {
            jdbc.update("INSERT INTO TEST_OUTCOME (RUN_ID, SUITE, TEST_ID, REQUIREMENT_REFS, STATUS, EXECUTED_AT, EVIDENCE_REF)"
                    + " VALUES (?, 'FIXTURE', 'acceptance', 'FR-001', 'PASSED', ?, 'fixture')", runId, now);
        } else {
            // tests ran and passed, but none is linked to an acceptance criterion
            jdbc.update("INSERT INTO TEST_OUTCOME (RUN_ID, SUITE, TEST_ID, STATUS, EXECUTED_AT) VALUES (?, 'FIXTURE', 'unit', 'PASSED', ?)",
                    runId, now);
        }
        if (omitted.contains(CompletionGate.Condition.TESTS_EXECUTED)) {
            jdbc.update("INSERT INTO TEST_OUTCOME (RUN_ID, SUITE, TEST_ID, STATUS, EXECUTED_AT) VALUES (?, 'FIXTURE', 'unit', 'FAILED', ?)",
                    runId, now);
        }
        if (!omitted.contains(CompletionGate.Condition.SECURITY_AND_RELIABILITY_CHECKED)) {
            for (String check : new String[] {"SEC-01", "SEC-02", "SEC-03"}) {
                pass(runId, check);
            }
        }
        if (!omitted.contains(CompletionGate.Condition.DOCUMENTATION_CURRENT)) {
            artifacts.record(runId, "DOC", "FIXTURE documentation", "DETERMINISTIC", "N10");
        }
        if (!omitted.contains(CompletionGate.Condition.TRACEABILITY_COMPLETE)) {
            pass(runId, "CHG-03");
        }
        if (!omitted.contains(CompletionGate.Condition.LIMITATIONS_DISCLOSED)) {
            jdbc.update("INSERT INTO ENGINEERING_SUMMARY (RUN_ID, CONTENT, CREATED_AT) VALUES (?, ?, ?)", runId,
                    "{\"limitations\":[\"FIXTURE limitation\"]}", now);
        }
    }

    private void pass(String runId, String check) {
        jdbc.update("INSERT INTO POLICY_EVALUATION (RUN_ID, CHECK_ID, OUTCOME, EVIDENCE_REF) VALUES (?, ?, 'PASS', 'fixture')", runId, check);
    }
}
