package com.agenticengineering.urlshortener.tests.integration;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.tests.contract.OpenApiContractHarness;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SCN-A link status lookup over HTTP (plan section 11; run run-934a50a1-35b3-4b72-85a3-4c66625a9969, task t4):
 * GET /api/v1/links/{code}/status answers 200 with ACTIVE, EXPIRED or UNKNOWN and the expiry time, validated against
 * contracts/openapi.yaml. The lookup is read-only: it never redirects and records no redirect event (FR-012 counts
 * redirect attempts only). Named *Test so the N09 default pattern executes it. Requirements: SCN-A, FR-040, FR-006.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkStatusApiTest {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

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
    void anActiveLinkReportsActiveWithItsExpiry() {
        Instant expiresAt = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        insert("stat0001", expiresAt);

        Response response = status("stat0001");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("short_code")).isEqualTo("stat0001");
        assertThat(response.jsonPath().getString("status")).isEqualTo("ACTIVE");
        assertThat(Instant.parse(response.jsonPath().getString("expires_at"))).isEqualTo(expiresAt);
    }

    @Test
    void anExpiredLinkReportsExpiredWithItsExpiry() {
        Instant expiresAt = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        insert("stat0002", expiresAt);

        Response response = status("stat0002");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("EXPIRED");
        assertThat(Instant.parse(response.jsonPath().getString("expires_at"))).isEqualTo(expiresAt);
    }

    @Test
    void anUnknownCodeReportsUnknownWithoutExpiry() {
        Response response = status("stat9999");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("UNKNOWN");
        assertThat(response.jsonPath().getMap("$")).doesNotContainKey("expires_at");
    }

    @Test
    void theLookupNeverRedirectsOrRecordsARedirectEvent() {
        insert("stat0003", Instant.now().plus(Duration.ofDays(1)));

        Response response = status("stat0003");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Location")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REDIRECT_EVENT", Integer.class)).isZero();
    }

    private static Response status(String code) {
        return given().redirects().follow(false).filter(CONTRACT.restAssuredFilter()).get("/api/v1/links/" + code + "/status");
    }

    private void insert(String code, Instant expiresAt) {
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, ?, ?, ?, ?, ?)", code, "https://example.com/" + code, "key-" + code, "d".repeat(64),
                Timestamp.from(expiresAt.minus(Duration.ofDays(7))), Timestamp.from(expiresAt));
    }
}
