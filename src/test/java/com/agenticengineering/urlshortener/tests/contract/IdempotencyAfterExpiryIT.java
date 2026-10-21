package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T109: the idempotency binding lives as long as the link row (ADR-006 amendment, human decision 2026-10-19): a replay
 * after the link has expired returns the original link unchanged with 200 - never 410, never a new link.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyAfterExpiryIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();
    private static final String BODY = "{\"target_url\":\"https://example.com/expiring\"}";

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
    void replayAfterExpiryReturnsTheOriginalLinkWith200() {
        Response created = create();
        String code = created.jsonPath().getString("short_code");
        // move the link into the past: it is now expired
        jdbc.update("UPDATE SHORT_LINK SET CREATED_AT = ?, EXPIRES_AT = ? WHERE SHORT_CODE = ?",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), Timestamp.from(Instant.parse("2026-01-08T00:00:00Z")), code);
        given().redirects().follow(false).get("/" + code).then().statusCode(410);

        Response replay = create();

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.jsonPath().getString("short_code")).isEqualTo(code);
        assertThat(replay.jsonPath().getString("expires_at")).isEqualTo("2026-01-08T00:00:00Z");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(1);
    }

    private Response create() {
        return given().filter(CONTRACT.restAssuredFilter()).contentType(ContentType.JSON)
                .header("Idempotency-Key", "key-expiry-replay").body(BODY).post("/api/v1/links");
    }
}
