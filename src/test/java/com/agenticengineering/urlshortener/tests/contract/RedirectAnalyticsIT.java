package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** T039: every resolution writes one REDIRECT_EVENT in the redirect path (FR-012), with no personal data columns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedirectAnalyticsIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM REDIRECT_EVENT");
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void redirectedAndExpiredAttemptsAreRecordedAgainstTheirLink() {
        long active = insert("anlt0001", Instant.now().plus(Duration.ofDays(1)));
        long expired = insert("anlt0002", Instant.now().minusSeconds(5));

        given().redirects().follow(false).get("/anlt0001").then().statusCode(302);
        given().redirects().follow(false).get("/anlt0001").then().statusCode(302);
        given().redirects().follow(false).get("/anlt0002").then().statusCode(410);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT SHORT_LINK_ID, ATTEMPTED_CODE, OUTCOME FROM REDIRECT_EVENT ORDER BY ID");
        assertThat(rows).extracting(r -> r.get("OUTCOME")).containsExactly("REDIRECTED", "REDIRECTED", "EXPIRED");
        assertThat(rows).extracting(r -> ((Number) r.get("SHORT_LINK_ID")).longValue()).containsExactly(active, active, expired);
        assertThat(rows).extracting(r -> r.get("ATTEMPTED_CODE")).containsExactly("anlt0001", "anlt0001", "anlt0002");
    }

    @Test
    void redirectEventsHoldNoPersonalData() {
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'REDIRECT_EVENT'",
                String.class)).containsExactlyInAnyOrder("ID", "SHORT_LINK_ID", "ATTEMPTED_CODE", "OCCURRED_AT", "OUTCOME");
    }

    private long insert(String code, Instant expiresAt) {
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, 'https://example.com/a', ?, ?, ?, ?)", code, "key-" + code, "e".repeat(64),
                Timestamp.from(expiresAt.minus(Duration.ofDays(7))), Timestamp.from(expiresAt));
        return jdbc.queryForObject("SELECT ID FROM SHORT_LINK WHERE SHORT_CODE = ?", Long.class, code);
    }
}
