package com.agenticengineering.urlshortener.orchestration.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** RELEASE_DECISION: the system's recommendation per run and the human decision on it (FR-037). One row per run. */
@Component
public class ReleaseDecisionStore {

    private final JdbcTemplate jdbc;

    public ReleaseDecisionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordRecommendation(String runId, String recommendation, List<String> blockingReasons, String requesterKeyId) {
        jdbc.update("MERGE INTO RELEASE_DECISION (RUN_ID, RECOMMENDATION, BLOCKING_REASONS, DECISION, REQUESTER_KEY_ID) KEY (RUN_ID)"
                + " VALUES (?, ?, ?, 'PENDING', ?)", runId, recommendation, String.join("; ", blockingReasons), requesterKeyId);
    }

    public Optional<String> recommendation(String runId) {
        List<String> rows = jdbc.queryForList("SELECT RECOMMENDATION FROM RELEASE_DECISION WHERE RUN_ID = ?", String.class, runId);
        return rows.stream().findFirst();
    }

    public Optional<Map<String, Object>> row(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM RELEASE_DECISION WHERE RUN_ID = ?", runId);
        return rows.stream().findFirst();
    }
}
