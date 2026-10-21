package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T037: GET /{code} over HTTP (FR-005, FR-006, FR-014; 302/410 per the 2026-10-19 decision). Expiry is derived at
 * resolution from expires_at (T038). Links are inserted with chosen timestamps so no test waits for a TTL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedirectIT {

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
    void activeCodeRedirectsWith302ToItsTarget() {
        insert("actv1234", "https://example.com/docs", Instant.now().plus(Duration.ofDays(1)));

        Response response = get("actv1234", true);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.header("Location")).isEqualTo("https://example.com/docs");
    }

    @Test
    void expiredCodeIs410WithoutALocation() {
        insert("expd1234", "https://example.com/old", Instant.now().minusSeconds(1));

        Response response = get("expd1234", true);

        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(response.header("Location")).isNull();
        assertThat(response.jsonPath().getString("code")).isEqualTo("LINK_EXPIRED");
    }

    @Test
    void unknownCodeIs404WithoutALocation() {
        Response response = get("none1234", true);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.header("Location")).isNull();
        assertThat(response.jsonPath().getString("code")).isEqualTo("LINK_NOT_FOUND");
    }

    @Test
    void malformedCodeIs404() {
        Response response = get("ab-c", false);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("LINK_NOT_FOUND");
        assertThat(CONTRACT.responseViolations("GET", "/abcd", 404, response.asString())).isEmpty();
    }

    @Test
    void reservedRoutesAreNotShadowedByTheRedirect() {
        assertThat(given().redirects().follow(false).get("/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void expirationRaceNeverRedirectsAfterTheFirst410() throws Exception {
        insert("race1234", "https://example.com/race", Instant.now().plusMillis(1500));
        List<Integer> statuses = new ArrayList<>();
        Instant stop = Instant.now().plusSeconds(4);
        while (Instant.now().isBefore(stop)) {
            statuses.add(get("race1234", false).statusCode());
            Thread.sleep(50);
        }

        assertThat(statuses).contains(302, 410).containsOnly(302, 410);
        int first410 = statuses.indexOf(410);
        assertThat(statuses.subList(first410, statuses.size())).as("once expired, never redirects again").containsOnly(410);
    }

    private Response get(String code, boolean validateAgainstContract) {
        var request = given().redirects().follow(false);
        if (validateAgainstContract) {
            request = request.filter(CONTRACT.restAssuredFilter());
        }
        return request.get("/" + code);
    }

    private void insert(String code, String target, Instant expiresAt) {
        Instant createdAt = expiresAt.minus(Duration.ofDays(7));
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, ?, ?, ?, ?, ?)", code, target, "key-" + code, "d".repeat(64), Timestamp.from(createdAt),
                Timestamp.from(expiresAt));
    }
}
