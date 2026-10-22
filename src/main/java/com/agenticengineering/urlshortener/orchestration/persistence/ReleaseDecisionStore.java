package com.agenticengineering.urlshortener.orchestration.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RELEASE_DECISION: the system's recommendation per run and the human decision on it (FR-037). One row per run.
 * Blocking reasons are stored one per line; when they exceed the column the tail is summarized and points at
 * POLICY_EVALUATION, which keeps every outcome.
 */
@Component
public class ReleaseDecisionStore {

    private static final int REASONS_MAX = 2048;

    public record ReleaseDecision(String runId, String recommendation, List<String> blockingReasons, String decision,
            String approverKeyId, Instant decidedAt, String evidenceRef) {
    }

    private final JdbcTemplate jdbc;

    public ReleaseDecisionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordRecommendation(String runId, String recommendation, List<String> blockingReasons, String requesterKeyId) {
        jdbc.update("MERGE INTO RELEASE_DECISION (RUN_ID, RECOMMENDATION, BLOCKING_REASONS, DECISION, REQUESTER_KEY_ID) KEY (RUN_ID)"
                + " VALUES (?, ?, ?, 'PENDING', ?)", runId, recommendation, fitReasons(blockingReasons), requesterKeyId);
    }

    /** Records the human decision; returns false when the run has no determination (fixture-only N12 runs). */
    public boolean recordDecision(String runId, boolean approved, String approverKeyId, Instant decidedAt, String evidenceRef) {
        return jdbc.update("UPDATE RELEASE_DECISION SET DECISION = ?, APPROVER_KEY_ID = ?, DECIDED_AT = ?, EVIDENCE_REF = ?"
                + " WHERE RUN_ID = ? AND DECISION = 'PENDING'", approved ? "APPROVED" : "REJECTED", approverKeyId,
                Timestamp.from(decidedAt), evidenceRef, runId) == 1;
    }

    public Optional<String> recommendation(String runId) {
        return find(runId).map(ReleaseDecision::recommendation);
    }

    public Optional<ReleaseDecision> find(String runId) {
        return jdbc.query("SELECT * FROM RELEASE_DECISION WHERE RUN_ID = ?", (rs, row) -> {
            String reasons = rs.getString("BLOCKING_REASONS");
            Timestamp decidedAt = rs.getTimestamp("DECIDED_AT");
            return new ReleaseDecision(rs.getString("RUN_ID"), rs.getString("RECOMMENDATION"),
                    reasons == null || reasons.isEmpty() ? List.of() : Arrays.asList(reasons.split("\n")), rs.getString("DECISION"),
                    rs.getString("APPROVER_KEY_ID"), decidedAt == null ? null : decidedAt.toInstant(), rs.getString("EVIDENCE_REF"));
        }, runId).stream().findFirst();
    }

    public Optional<Map<String, Object>> row(String runId) {
        return jdbc.queryForList("SELECT * FROM RELEASE_DECISION WHERE RUN_ID = ?", runId).stream().findFirst();
    }

    /** One reason per line within the column; whatever does not fit is summarized as a count pointing at POLICY_EVALUATION. */
    public static String fitReasons(List<String> reasons) {
        int budget = REASONS_MAX - 64; // room for the summary line
        List<String> kept = new ArrayList<>();
        int length = 0;
        for (int i = 0; i < reasons.size(); i++) {
            String line = reasons.get(i).replace('\n', ' ');
            if (length + line.length() > budget) {
                kept.add("... " + (reasons.size() - i) + " more; see POLICY_EVALUATION");
                break;
            }
            kept.add(line);
            length += line.length() + 1;
        }
        return String.join("\n", kept);
    }
}
