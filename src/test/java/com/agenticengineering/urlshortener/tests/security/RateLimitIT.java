package com.agenticengineering.urlshortener.tests.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.audit.PlatformAuditChain;
import com.agenticengineering.urlshortener.tests.contract.OpenApiContractHarness;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * T041: per-IP limits on creation (A-008a) and redirect (A-008b), low values from the lowlimits test profile
 * (creation 3, redirect 5). Over-limit: 429 with Retry-After and retry_after_seconds, no short code allocated, audited
 * on the platform chain (FR-047).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("lowlimits")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformAuditChain platformAudit;

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
    void creationOverTheLimitIs429AllocatesNothingAndIsAudited() {
        for (int i = 0; i < 3; i++) {
            assertThat(create("key-ratelimit-" + i).statusCode()).isEqualTo(201);
        }
        Response limited = create("key-ratelimit-9");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.jsonPath().getString("code")).isEqualTo("RATE_LIMITED");
        long retryAfter = Long.parseLong(limited.header("Retry-After"));
        assertThat(retryAfter).isPositive();
        assertThat(limited.jsonPath().getLong("retry_after_seconds")).isEqualTo(retryAfter);
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", 429, limited.asString())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SHORT_LINK", Integer.class)).isEqualTo(3);

        List<String> actions = jdbc.queryForList("SELECT ACTION FROM PLATFORM_AUDIT_EVENT ORDER BY SEQ", String.class);
        assertThat(actions).containsExactly("RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT AFFECTED_ARTIFACT FROM PLATFORM_AUDIT_EVENT", String.class))
                .isEqualTo("POST /api/v1/links");
        assertThat(platformAudit.verify().intact()).isTrue();
    }

    @Test
    void redirectOverTheLimitIs429AndRecordsNoAnalytics() {
        for (int i = 0; i < 5; i++) {
            assertThat(given().redirects().follow(false).get("/none000" + i).statusCode()).isEqualTo(404);
        }
        Response limited = given().redirects().follow(false).get("/none0009");

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        assertThat(limited.header("Location")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM REDIRECT_EVENT", Integer.class)).isEqualTo(5);
    }

    @Test
    void otherEndpointsAreNotLimitedByTheseBuckets() {
        for (int i = 0; i < 10; i++) {
            assertThat(given().get("/api/v1/analytics/summary").statusCode()).isEqualTo(200);
            assertThat(given().get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    private Response create(String key) {
        return given().contentType(ContentType.JSON).header("Idempotency-Key", key)
                .body("{\"target_url\":\"https://example.com/limited\"}").post("/api/v1/links");
    }
}
