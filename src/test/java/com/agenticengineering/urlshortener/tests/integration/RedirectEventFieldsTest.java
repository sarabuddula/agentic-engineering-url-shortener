package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PRV-01 evidence (FR-012, FR-015, A-002): redirect analytics hold exactly the approved fields - the link, the attempted
 * code, the time and the outcome - and no client data (no IP, user agent or referrer). Evidence test for the N12
 * check, written after the schema (not red-first).
 */
@JdbcTest
class RedirectEventFieldsTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void redirectEventsHoldOnlyTheApprovedFields() {
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'REDIRECT_EVENT'", String.class))
                .containsExactlyInAnyOrder("ID", "SHORT_LINK_ID", "ATTEMPTED_CODE", "OCCURRED_AT", "OUTCOME");
    }
}
