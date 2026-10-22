package com.agenticengineering.urlshortener.tests.e2e;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
 * T047: the US-001 journey end to end over HTTP (FR-001, FR-005, FR-006, FR-007, FR-011, FR-012, SC-001):
 * create -> redirect -> expire -> 410 -> analytics shows both outcomes -> idempotent replay -> conflict.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppPlaneE2EIT {

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
    void apiConsumerJourney() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
        String body = "{\"target_url\":\"https://example.com/journey\",\"expires_at\":\"" + expiresAt + "\"}";

        Response created = create("key-journey-01", body);
        assertThat(created.statusCode()).isEqualTo(201);
        String code = created.jsonPath().getString("short_code");

        Response redirect = given().redirects().follow(false).get("/" + code);
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.header("Location")).isEqualTo("https://example.com/journey");

        while (Instant.now().isBefore(expiresAt.plus(Duration.ofMillis(200)))) {
            Thread.sleep(100);
        }
        Response expired = given().redirects().follow(false).get("/" + code);
        assertThat(expired.statusCode()).isEqualTo(410);
        assertThat(expired.header("Location")).isNull();

        Response summary = given().queryParam("code", code).get("/api/v1/analytics/summary");
        assertThat(summary.jsonPath().getInt("redirected")).isEqualTo(1);
        assertThat(summary.jsonPath().getInt("expired")).isEqualTo(1);

        Response replay = create("key-journey-01", body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.jsonPath().getString("short_code")).isEqualTo(code);

        Response conflict = create("key-journey-01", "{\"target_url\":\"https://example.com/other\"}");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(1);
    }

    private static Response create(String key, String body) {
        return given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body).post("/api/v1/links");
    }
}
