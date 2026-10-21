package com.agenticengineering.urlshortener.shortener.persistence;

import com.agenticengineering.urlshortener.shortener.analytics.AnalyticsStore;
import com.agenticengineering.urlshortener.shortener.analytics.RedirectEvent;
import com.agenticengineering.urlshortener.shortener.analytics.Summary;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** REDIRECT_EVENT and LOSS_REPORT on Spring JDBC (T039, T040; ADR-003). Insert and aggregate only. */
@Repository
public class JdbcAnalyticsStore implements AnalyticsStore {

    private final JdbcTemplate jdbc;

    public JdbcAnalyticsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RedirectEvent event) {
        jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME) VALUES (?, ?, ?, ?)",
                event.shortLinkId(), event.attemptedCode(), Timestamp.from(event.occurredAt()), event.outcome().name());
    }

    @Override
    public long insertLossReport(Instant periodStart, Instant periodEnd, long unrecordedCount) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO LOSS_REPORT (PERIOD_START, PERIOD_END, UNRECORDED_COUNT) VALUES (?, ?, ?)", new String[] {"ID"});
            statement.setTimestamp(1, Timestamp.from(periodStart));
            statement.setTimestamp(2, Timestamp.from(periodEnd));
            statement.setLong(3, unrecordedCount);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    @Override
    public Summary summary(String code) {
        String filter = code == null ? "" : " WHERE ATTEMPTED_CODE = ?";
        Object[] args = code == null ? new Object[0] : new Object[] {code};
        Map<String, Object> counts = jdbc.queryForMap("SELECT"
                + " COALESCE(SUM(CASE WHEN OUTCOME = 'REDIRECTED' THEN 1 ELSE 0 END), 0) AS REDIRECTED,"
                + " COALESCE(SUM(CASE WHEN OUTCOME = 'EXPIRED' THEN 1 ELSE 0 END), 0) AS EXPIRED,"
                + " COALESCE(SUM(CASE WHEN OUTCOME = 'NOT_FOUND' THEN 1 ELSE 0 END), 0) AS NOT_FOUND"
                + " FROM REDIRECT_EVENT" + filter, args);
        Map<String, Object> loss = jdbc.queryForMap(
                "SELECT COALESCE(SUM(UNRECORDED_COUNT), 0) AS TOTAL, MAX(ID) AS LATEST FROM LOSS_REPORT");
        return new Summary(code, number(counts.get("REDIRECTED")), number(counts.get("EXPIRED")), number(counts.get("NOT_FOUND")),
                number(loss.get("TOTAL")), loss.get("LATEST") == null ? null : ((Number) loss.get("LATEST")).longValue());
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
