package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * T018: application-plane schema (data-model.md SHORT_LINK, REDIRECT_EVENT, LOSS_REPORT) enforces its constraints in
 * the database, not in application checks (PVT-003).
 */
@JdbcTest
class ApplicationSchemaTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-21T12:00:00Z"));
    private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-10-28T12:00:00Z"));
    private static final String HASH = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void schemaCreatesTheThreeApplicationPlaneTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class);

        assertThat(tables).contains("SHORT_LINK", "REDIRECT_EVENT", "LOSS_REPORT");
    }

    @Test
    void expiryStatusIsNotStoredOnShortLink() {
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SHORT_LINK'", String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "ID", "SHORT_CODE", "TARGET_URL", "REQUEST_IDENTITY", "PAYLOAD_HASH", "CREATED_AT", "EXPIRES_AT");
    }

    @Test
    void duplicateShortCodeIsRejected() {
        insertLink("abcd", "key-00000001");

        assertThatThrownBy(() -> insertLink("abcd", "key-00000002")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateRequestIdentityIsRejected() {
        insertLink("abcd", "key-00000001");

        assertThatThrownBy(() -> insertLink("wxyz", "key-00000001")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void expiryMustFollowCreation() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH,"
                + " CREATED_AT, EXPIRES_AT) VALUES ('abcd', 'https://example.com/', 'key-00000001', ?, ?, ?)", HASH, LATER, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notFoundEventsHaveNoLinkAndOtherOutcomesRequireOne() {
        long linkId = insertLink("abcd", "key-00000001");

        jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME) VALUES (NULL, 'nope', ?, 'NOT_FOUND')", NOW);
        jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME) VALUES (?, 'abcd', ?, 'REDIRECTED')", linkId, NOW);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME)"
                + " VALUES (NULL, 'abcd', ?, 'REDIRECTED')", NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME)"
                + " VALUES (?, 'abcd', ?, 'CLICKED')", linkId, NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO REDIRECT_EVENT (SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT, OUTCOME)"
                + " VALUES (999999, 'abcd', ?, 'EXPIRED')", NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lossReportCountsAreNonNegativeOverAValidPeriod() {
        jdbc.update("INSERT INTO LOSS_REPORT (PERIOD_START, PERIOD_END, UNRECORDED_COUNT) VALUES (?, ?, 2)", NOW, LATER);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO LOSS_REPORT (PERIOD_START, PERIOD_END, UNRECORDED_COUNT)"
                + " VALUES (?, ?, -1)", NOW, LATER)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO LOSS_REPORT (PERIOD_START, PERIOD_END, UNRECORDED_COUNT)"
                + " VALUES (?, ?, 0)", LATER, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaScriptCanRunAgainOnAnExistingDatabase() {
        // kill-and-restart against the same H2 file (PVT-006) re-runs the whole script (both planes) on existing tables;
        // no rows are seeded first because DDL commits implicitly in H2
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isZero();
    }

    private long insertLink(String code, String requestIdentity) {
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, 'https://example.com/', ?, ?, ?, ?)", code, requestIdentity, HASH, NOW, LATER);
        return jdbc.queryForObject("SELECT ID FROM SHORT_LINK WHERE SHORT_CODE = ?", Long.class, code);
    }
}
