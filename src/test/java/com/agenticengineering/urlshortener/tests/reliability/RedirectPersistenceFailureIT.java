package com.agenticengineering.urlshortener.tests.reliability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.agenticengineering.urlshortener.shortener.persistence.JdbcLinkRepository;
import com.agenticengineering.urlshortener.tests.contract.OpenApiContractHarness;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * T102: fault injection in the redirect path (FR-009). Transient failures within the bound still redirect; exhausted
 * retries give the defined 503 SERVICE_UNAVAILABLE and permanent failures 500, never a partial or wrong redirect.
 * Backoff is shortened for the test; the retry count stays the approved 3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.persistence-retry.initial-backoff=10ms")
class RedirectPersistenceFailureIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private JdbcLinkRepository repository;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        Instant expiresAt = Instant.now().plus(Duration.ofDays(1));
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES ('flty0001', 'https://example.com/f', 'key-flty0001', ?, ?, ?)", "3".repeat(64),
                Timestamp.from(expiresAt.minus(Duration.ofDays(7))), Timestamp.from(expiresAt));
    }

    @AfterEach
    void cleanUp() {
        reset(repository);
        jdbc.update("DELETE FROM REDIRECT_EVENT");
        jdbc.update("DELETE FROM SHORT_LINK");
    }

    @Test
    void transientFailuresWithinTheBoundStillRedirect() {
        doThrow(new CannotGetJdbcConnectionException("blip")).doThrow(new CannotGetJdbcConnectionException("blip"))
                .doCallRealMethod().when(repository).findByCode(any());

        Response response = given().redirects().follow(false).get("/flty0001");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.header("Location")).isEqualTo("https://example.com/f");
    }

    @Test
    void exhaustedRetriesGiveTheDefined503WithoutARedirect() {
        doThrow(new CannotGetJdbcConnectionException("down")).when(repository).findByCode(any());

        Response response = given().redirects().follow(false).get("/flty0001");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.header("Location")).isNull();
        assertThat(response.jsonPath().getString("code")).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(CONTRACT.responseViolations("GET", "/flty0001", 503, response.asString())).isEmpty();
    }

    @Test
    void permanentFailuresGive500WithoutARedirect() {
        doThrow(new DataIntegrityViolationException("corrupt row")).when(repository).findByCode(any());

        Response response = given().redirects().follow(false).get("/flty0001");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.header("Location")).isNull();
        assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_ERROR");
    }
}
