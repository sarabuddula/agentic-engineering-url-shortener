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

    public String content(String runId) {
        return jdbc.queryForObject("SELECT CONTENT FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ? ORDER BY VERSION DESC LIMIT 1",
                String.class, runId);
    }
}
