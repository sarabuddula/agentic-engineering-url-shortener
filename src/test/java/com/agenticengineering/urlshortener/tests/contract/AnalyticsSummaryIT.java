package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T040: GET /api/v1/analytics/summary (FR-012 reporting), public, with and without the code filter, validated against
 * the contract. Successful redirects are distinguished from other outcomes; recorded loss is reported (FR-015).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsSummaryIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        insert("sumr0001", Instant.now().plus(Duration.ofDays(1)));
        insert("sumr0002", Instant.now().minusSeconds(5));
        redirect("sumr0001");
        redirect("sumr0001");
        redirect("sumr0002");
        redirect("none0001");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM REDIRECT_EVENT");
        jdbc.update("DELETE FROM LOSS_REPORT");
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void summaryOverAllCodesDistinguishesOutcomes() {
        Response response = given().filter(CONTRACT.restAssuredFilter()).get("/api/v1/analytics/summary");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("window")).isEqualTo("all-time");
        assertThat(response.jsonPath().getInt("redirected")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("expired")).isEqualTo(1);
        assertThat(response.jsonPath().getInt("not_found")).isEqualTo(1);
        assertThat(response.jsonPath().getInt("unrecorded_capture_failures")).isZero();
        assertThat(response.jsonPath().getMap("$")).doesNotContainKey("code");
    }

    @Test
    void summaryScopedToOneCodeCountsOnlyThatCode() {
        Response response = given().filter(CONTRACT.restAssuredFilter()).queryParam("code", "sumr0001").get("/api/v1/analytics/summary");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("code")).isEqualTo("sumr0001");
        assertThat(response.jsonPath().getInt("redirected")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("expired")).isZero();
        assertThat(response.jsonPath().getInt("not_found")).isZero();
    }

    @Test
    void reportedLossAppearsInTheUnscopedSummary() {
        jdbc.update("INSERT INTO LOSS_REPORT (PERIOD_START, PERIOD_END, UNRECORDED_COUNT) VALUES (?, ?, 3)",
                Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now()));
        long id = jdbc.queryForObject("SELECT MAX(ID) FROM LOSS_REPORT", Long.class);

        Response response = given().filter(CONTRACT.restAssuredFilter()).get("/api/v1/analytics/summary");

        assertThat(response.jsonPath().getInt("unrecorded_capture_failures")).isEqualTo(3);
        assertThat(response.jsonPath().getString("loss_report_ref")).isEqualTo("loss-report-" + id);
    }

    @Test
    void malformedCodeFilterIs400() {
        Response response = given().queryParam("code", "ab-c").get("/api/v1/analytics/summary");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
    }

    private void redirect(String code) {
        given().redirects().follow(false).get("/" + code);
    }

    private void insert(String code, Instant expiresAt) {
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, 'https://example.com/a', ?, ?, ?, ?)", code, "key-" + code, "f".repeat(64),
                Timestamp.from(expiresAt.minus(Duration.ofDays(7))), Timestamp.from(expiresAt));
    }
}
