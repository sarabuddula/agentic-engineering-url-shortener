package com.agenticengineering.urlshortener.orchestration.reliability;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * Shared failure recording for MTTR (T048; FR-031, plan section 7). Every failure path records detection, the
 * recovery start and mechanism, and recovery completion, or stays unrecovered. Governance wait is captured separately
 * so the primary MTTR excludes it (human decision 2026-10-19). Timestamps are stored at microsecond precision.
 */
@Component
public class FailureRecorder {

    private final JdbcTemplate jdbc;

    public FailureRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long detected(String runId, long nodeId, Instant at) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO FAILURE_EVENT (RUN_ID, NODE_ID, FAILURE_DETECTED_AT,"
                    + " RECOVERED, GOVERNANCE_WAIT_MS, MTTR_EXCLUDED) VALUES (?, ?, ?, FALSE, 0, FALSE)", new String[] {"ID"});
            statement.setString(1, runId);
            statement.setLong(2, nodeId);
            statement.setTimestamp(3, ts(at));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public void recoveryStarted(long id, Instant at, RecoveryMechanism mechanism) {
        FailureEvent event = find(id);
        if (at.isBefore(event.failureDetectedAt())) {
            throw new IllegalArgumentException("recovery cannot start before the failure was detected");
        }
        jdbc.update("UPDATE FAILURE_EVENT SET RECOVERY_START_AT = ?, RECOVERY_MECHANISM = ? WHERE ID = ?",
                ts(at), mechanism.code(), id);
    }

    /** The recovery moved to another mechanism (FP-1 retries exhausted, FP-2 fallback); the recovery start is kept. */
    public void switchMechanism(long id, RecoveryMechanism mechanism) {
        jdbc.update("UPDATE FAILURE_EVENT SET RECOVERY_MECHANISM = ? WHERE ID = ? AND RECOVERY_START_AT IS NOT NULL", mechanism.code(), id);
    }

    public void recovered(long id, Instant at) {
        FailureEvent event = find(id);
        if (event.recoveryStartAt() == null) {
            throw new IllegalStateException("failure " + id + " has no recovery start");
        }
        if (at.isBefore(event.recoveryStartAt())) {
            throw new IllegalArgumentException("recovery cannot complete before it started");
        }
        jdbc.update("UPDATE FAILURE_EVENT SET RECOVERY_COMPLETE_AT = ?, RECOVERED = TRUE WHERE ID = ?", ts(at), id);
    }

    public void addGovernanceWait(long id, Duration wait) {
        if (wait.isNegative()) {
            throw new IllegalArgumentException("governance wait cannot be negative");
        }
        jdbc.update("UPDATE FAILURE_EVENT SET GOVERNANCE_WAIT_MS = GOVERNANCE_WAIT_MS + ? WHERE ID = ?", wait.toMillis(), id);
    }

    public void excludeFromMttr(long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an MTTR exclusion needs a reason");
        }
        jdbc.update("UPDATE FAILURE_EVENT SET MTTR_EXCLUDED = TRUE, MTTR_EXCLUSION_REASON = ? WHERE ID = ?", reason, id);
    }

    /** Every failure event of a run, in detection order (input to MttrCalculator). */
    public java.util.List<FailureEvent> forRun(String runId) {
        return jdbc.queryForList("SELECT ID FROM FAILURE_EVENT WHERE RUN_ID = ? ORDER BY ID", Long.class, runId).stream()
                .map(this::find).toList();
    }

    public FailureEvent find(long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM FAILURE_EVENT WHERE ID = ?", id);
        String mechanism = (String) row.get("RECOVERY_MECHANISM");
        return new FailureEvent(id, (String) row.get("RUN_ID"), ((Number) row.get("NODE_ID")).longValue(),
                instant(row.get("FAILURE_DETECTED_AT")), instant(row.get("RECOVERY_START_AT")),
                instant(row.get("RECOVERY_COMPLETE_AT")), mechanism == null ? null : RecoveryMechanism.fromCode(mechanism),
                (Boolean) row.get("RECOVERED"), Duration.ofMillis(((Number) row.get("GOVERNANCE_WAIT_MS")).longValue()),
                (Boolean) row.get("MTTR_EXCLUDED"), (String) row.get("MTTR_EXCLUSION_REASON"));
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at.truncatedTo(ChronoUnit.MICROS));
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type " + value.getClass());
    }
}
