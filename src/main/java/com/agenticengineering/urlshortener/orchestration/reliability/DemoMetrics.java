package com.agenticengineering.urlshortener.orchestration.reliability;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Demonstration metrics (T083, T104; FR-031, C-004, PVT-008) over every run in this database. Always labelled
 * DEMONSTRATION: the population is demonstration runs with fault injection, never production load. Counts come from the
 * audit chain and FAILURE_EVENT; MTTR from MttrCalculator; end-to-end latency is run creation to the terminal transition
 * (audited), averaged over terminal runs. p95 redirect latency is not persisted (PerformanceIT measures it in its own
 * report) and p95 end-to-end latency is deferred (T104 reduced), so both are omitted rather than invented.
 */
@Component
public class DemoMetrics {

    private final JdbcTemplate jdbc;
    private final FailureRecorder failures;

    public DemoMetrics(JdbcTemplate jdbc, FailureRecorder failures) {
        this.jdbc = jdbc;
        this.failures = failures;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("measurement_class", "DEMONSTRATION");
        int finished = count("SELECT COUNT(*) FROM WORKFLOW_RUN WHERE STATUS IN ('COMPLETED', 'TERMINATED', 'SAFE_STOPPED')");
        int completed = count("SELECT COUNT(*) FROM WORKFLOW_RUN WHERE STATUS = 'COMPLETED'");
        body.put("workflow_success_rate", finished == 0 ? 0.0 : (double) completed / finished);
        body.put("retry_count", count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'NODE_TRANSITION'"
                + " AND RESULT = 'FAILED->RETRY_WAITING' AND REASON LIKE 'FP-1%'"));
        body.put("rollback_count", count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'ROLLBACK_APPLIED'"));
        body.put("compensation_count", count("SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'COMPENSATION_APPLIED'"));
        List<FailureEvent> events = jdbc.queryForList("SELECT ID FROM FAILURE_EVENT ORDER BY ID", Long.class).stream()
                .map(failures::find).toList();
        MttrReport mttr = MttrCalculator.compute(events);
        body.put("mttr_ms", mttr.mttr().map(Duration::toMillis).orElse(null));
        body.put("recovered_failure_events", mttr.recoveredCount());
        body.put("unrecovered_failure_events", mttr.unrecoveredCount());
        body.put("governance_wait_ms_total", mttr.totalGovernanceWait().toMillis());
        body.put("mttr_incl_governance_wait_ms", mttr.mttrIncludingGovernanceWait().map(Duration::toMillis).orElse(null));
        List<Long> latencies = jdbc.query("SELECT r.CREATED_AT, MIN(a.OCCURRED_AT) AS ENDED_AT FROM WORKFLOW_RUN r JOIN AUDIT_EVENT a"
                + " ON a.RUN_ID = r.ID WHERE a.ACTION = 'RUN_TRANSITION' AND (a.RESULT LIKE '%->COMPLETED' OR a.RESULT LIKE '%->TERMINATED')"
                + " GROUP BY r.ID, r.CREATED_AT", (rs, i) -> {
                    Timestamp start = rs.getTimestamp("CREATED_AT");
                    Timestamp end = rs.getTimestamp("ENDED_AT");
                    return Duration.between(start.toInstant(), end.toInstant()).toMillis();
                });
        if (!latencies.isEmpty()) {
            body.put("end_to_end_latency_ms", latencies.stream().mapToLong(Long::longValue).average().orElse(0));
        }
        return body;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
