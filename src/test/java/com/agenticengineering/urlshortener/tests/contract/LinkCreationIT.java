package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
 * T036: POST /api/v1/links over HTTP (FR-001, FR-002, FR-007, FR-011). Valid requests run through the OpenAPI
 * validation filter (request and response checked against contracts/openapi.yaml); for requests that are invalid by
 * design, the response alone is checked against the contract. No short code is allocated on any error path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkCreationIT {

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
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void createsALinkWithTheDefaultTtl() {
        Response response = create("key-create-01", "{\"target_url\":\"HTTPS://Example.com:443/docs\"}", true);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("short_code")).matches("[A-Za-z0-9]{8}");
        assertThat(response.jsonPath().getString("target_url")).isEqualTo("https://example.com/docs");
        Instant created = Instant.parse(response.jsonPath().getString("created_at"));
        // A-006 default TTL 24 hours (SCN-B CHANGE_APPROVAL 2026-10-22; was 7 days)
        assertThat(Instant.parse(response.jsonPath().getString("expires_at"))).isEqualTo(created.plus(Duration.ofHours(24)));
    }

    @Test
    void keepsAnExplicitExpiry() {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Response response = create("key-create-02",
                "{\"target_url\":\"https://example.com/report\",\"expires_at\":\"" + expiresAt + "\"}", true);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Instant.parse(response.jsonPath().getString("expires_at"))).isEqualTo(expiresAt);
    }

    @Test
    void replayReturnsTheSameLinkWith200() {
        Response first = create("key-replay-01", "{\"target_url\":\"https://example.com/a\"}", true);
        Response replay = create("key-replay-01", "{\"target_url\":\"https://example.com/a\"}", true);
        // surrounding whitespace is outside the contract's uri format, so this request bypasses the filter; the server
        // still normalizes it (FR-013) and treats it as the same payload
        Response untrimmedReplay = create("key-replay-01", "{\"target_url\":\" https://example.com/a \"}", false);

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.jsonPath().getMap("$")).isEqualTo(first.jsonPath().getMap("$"));
        assertThat(untrimmedReplay.statusCode()).isEqualTo(200);
        assertThat(untrimmedReplay.jsonPath().getMap("$")).isEqualTo(first.jsonPath().getMap("$"));
        assertThat(linkCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIs409AndChangesNothing() {
        create("key-conflict-1", "{\"target_url\":\"https://example.com/a\"}", true);
        Response conflict = create("key-conflict-1", "{\"target_url\":\"https://example.com/b\"}", false);

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.jsonPath().getString("code")).isEqualTo("REQUEST_IDENTITY_CONFLICT");
        assertResponseConforms(409, conflict);
        assertThat(jdbc.queryForObject("SELECT TARGET_URL FROM SHORT_LINK", String.class)).isEqualTo("https://example.com/a");
    }

    @Test
    void rejectedTargetsAre400UrlRejectedAndAllocateNothing() {
        for (String target : new String[] {"ftp://example.com/f", "http://10.0.0.1/", "http://localhost/", "https://user@example.com/",
                "not a url", "https://unlisted.example.net/"}) {
            Response response = create("key-reject-01", "{\"target_url\":\"" + target + "\"}", false);

            assertThat(response.statusCode()).as("status for %s", target).isEqualTo(400);
            assertThat(response.jsonPath().getString("code")).as("code for %s", target).isEqualTo("URL_REJECTED");
            assertResponseConforms(400, response);
        }
        assertThat(linkCount()).isZero();
    }

    @Test
    void missingOrShortIdempotencyKeyIs400() {
        Response missing = given().contentType(ContentType.JSON).body("{\"target_url\":\"https://example.com/a\"}")
                .post("/api/v1/links");
        Response tooShort = create("short", "{\"target_url\":\"https://example.com/a\"}", false);

        for (Response response : new Response[] {missing, tooShort}) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
            assertResponseConforms(400, response);
        }
        assertThat(linkCount()).isZero();
    }

    @Test
    void malformedBodiesAndPastExpiriesAre400() {
        Response malformed = create("key-badbody-1", "{not json", false);
        Response pastExpiry = create("key-badbody-2", "{\"target_url\":\"https://example.com/a\",\"expires_at\":\"2020-01-01T00:00:00Z\"}", false);
        Response badDate = create("key-badbody-3", "{\"target_url\":\"https://example.com/a\",\"expires_at\":\"tomorrow\"}", false);

        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
        assertThat(pastExpiry.statusCode()).isEqualTo(400);
        assertThat(pastExpiry.jsonPath().getString("code")).isEqualTo("INVALID_EXPIRATION");
        assertThat(badDate.statusCode()).isEqualTo(400);
        assertThat(badDate.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
        for (Response response : new Response[] {malformed, pastExpiry, badDate}) {
            assertResponseConforms(400, response);
        }
        assertThat(linkCount()).isZero();
    }

    private Response create(String idempotencyKey, String body, boolean validateAgainstContract) {
        var request = given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(body);
        if (validateAgainstContract) {
            request = request.filter(CONTRACT.restAssuredFilter());
        }
        return request.post("/api/v1/links");
    }

    private static void assertResponseConforms(int status, Response response) {
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", status, response.asString()))
                .as("contract violations for %s", response.asString()).isEmpty();
    }

    private int linkCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class);
    }
}
