package com.agenticengineering.urlshortener.orchestration.persistence;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** REQUIREMENT_ARTIFACT rows: the normalized requirement with provenance, versioned (N01, plan section 4). */
@Component
public class RequirementStore {

    /** Provenance values of the data model (CONFIRMED, DERIVED, PROPOSED). */
    public enum Provenance { CONFIRMED, DERIVED, PROPOSED }

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;

    public RequirementStore(JdbcTemplate jdbc, AuditChain audit, TimeSource clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Stores version 1 of the run's requirement and audits it; callers run this inside the N01 transition sequence. */
    public void saveIngested(String runId, String normalizedContent, Provenance provenance) {
        jdbc.update("INSERT INTO REQUIREMENT_ARTIFACT (RUN_ID, CONTENT, PROVENANCE, VERSION) VALUES (?, ?, ?, 1)", runId,
                normalizedContent, provenance.name());
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.AGENT, "REQUIREMENT_INGESTED", clock.now(), "N01",
                "VERSION 1", "requirement stored (" + normalizedContent.length() + " characters, provenance " + provenance + ")"));
    }

    /**
     * Stores the next requirement version after an approved G1 clarification (plan section 11, SCN-C): the previous text
     * plus the human answer, provenance CONFIRMED, with the approval it came from recorded in ASSUMPTIONS. A run without a
     * requirement artifact (created directly through the engine) has nothing to update.
     */
    public void saveClarified(String runId, long approvalId, String approverKeyId, String answer) {
        if (!exists(runId)) {
            return;
        }
        int version = jdbc.queryForObject("SELECT MAX(VERSION) FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?", Integer.class, runId) + 1;
        String content = content(runId) + "\nClarification (approval " + approvalId + "): " + answer;
        jdbc.update("INSERT INTO REQUIREMENT_ARTIFACT (RUN_ID, CONTENT, PROVENANCE, VERSION, ASSUMPTIONS) VALUES (?, ?, ?, ?, ?)",
                runId, content, Provenance.CONFIRMED.name(), version,
                "material findings resolved by clarification approval " + approvalId + " (" + approverKeyId + ")");
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "REQUIREMENT_CLARIFIED", clock.now(), "N03",
                "VERSION " + version, "requirement updated from clarification approval " + approvalId + " by " + approverKeyId
                        + " (provenance CONFIRMED)"));
    }

    /** True when the latest requirement version is a CONFIRMED clarification (its material findings are resolved). */
    public boolean clarified(String runId) {
        return jdbc.queryForList("SELECT PROVENANCE FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ? AND VERSION > 1 ORDER BY VERSION DESC LIMIT 1",
                String.class, runId).stream().anyMatch(Provenance.CONFIRMED.name()::equals);
    }

    /** Stores the N02 quality report (JSON) on the latest requirement version. */
    public void saveQualityReport(String runId, String reportJson) {
        jdbc.update("UPDATE REQUIREMENT_ARTIFACT SET QUALITY_REPORT = ? WHERE RUN_ID = ? AND VERSION ="
                + " (SELECT MAX(VERSION) FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?)", reportJson, runId, runId);
    }

    public boolean exists(String runId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?", Integer.class, runId) > 0;
    }

    public String requirementRef(String runId) {
        return jdbc.queryForObject("SELECT REQUIREMENT_REF FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
    }

    public java.util.Optional<String> qualityReport(String runId) {
        return jdbc.queryForList("SELECT CAST(QUALITY_REPORT AS VARCHAR) FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?"
                + " AND QUALITY_REPORT IS NOT NULL ORDER BY VERSION DESC LIMIT 1", String.class, runId).stream().findFirst();
    }

    public String content(String runId) {
        return jdbc.queryForObject("SELECT CONTENT FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ? ORDER BY VERSION DESC LIMIT 1",
                String.class, runId);
    }
}
