package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T044: the application plane against contracts/openapi.yaml (NFR-009, FR-002). First, every documented response example
 * of the application-plane paths conforms to its own schema. Second, one live request per documented status code
 * (201, 200, 400, 409 on creation; 302, 404, 410 on redirect; 200 on summary and health) passes contract validation;
 * 429 is exercised by RateLimitIT under the lowlimits profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppPlaneContractIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> CONCRETE_PATHS = Map.of(
            "/api/v1/links", "/api/v1/links", "/{code}", "/abcd1234",
            "/api/v1/analytics/summary", "/api/v1/analytics/summary", "/actuator/health", "/actuator/health");

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
    void everyApplicationPlaneResponseExampleConformsToItsSchema() throws Exception {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        OpenAPI api = new OpenAPIV3Parser().read(OpenApiContractHarness.SPECIFICATION.toString(), null, options);
        List<String> checked = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> path : CONCRETE_PATHS.entrySet()) {
            PathItem item = api.getPaths().get(path.getKey());
            for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                op.getValue().getResponses().forEach((status, response) -> {
                    if (response.getContent() == null) {
                        return;
                    }
                    MediaType media = response.getContent().get("application/json");
                    if (media == null || media.getExamples() == null) {
                        return;
                    }
                    for (Map.Entry<String, Example> example : media.getExamples().entrySet()) {
                        String label = op.getKey() + " " + path.getKey() + " " + status + " " + example.getKey();
                        try {
                            String body = JSON.writeValueAsString(example.getValue().getValue());
                            List<String> found = CONTRACT.responseViolations(op.getKey().name(), path.getValue(),
                                    Integer.parseInt(status), body);
                            checked.add(label);
                            found.forEach(v -> violations.add(label + ": " + v));
                        } catch (Exception e) {
                            violations.add(label + ": " + e.getMessage());
                        }
                    }
                });
            }
        }
        assertThat(checked).as("examples checked").hasSizeGreaterThanOrEqualTo(8);
        assertThat(violations).isEmpty();
    }

    @Test
    void creationStatusesConform() {
        assertThat(create("key-contract-1", "{\"target_url\":\"https://example.com/c\"}", true).statusCode()).isEqualTo(201);
        assertThat(create("key-contract-1", "{\"target_url\":\"https://example.com/c\"}", true).statusCode()).isEqualTo(200);
        Response conflict = create("key-contract-1", "{\"target_url\":\"https://example.com/d\"}", false);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", 409, conflict.asString())).isEmpty();
        Response rejected = create("key-contract-2", "{\"target_url\":\"ftp://example.com/c\"}", false);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/links", 400, rejected.asString())).isEmpty();
    }

    @Test
    void redirectStatusesConform() {
        insert("ctrc0001", Instant.now().plus(Duration.ofDays(1)));
        insert("ctrc0002", Instant.now().minusSeconds(5));

        assertThat(validatedGet("/ctrc0001").statusCode()).isEqualTo(302);
        assertThat(validatedGet("/ctrc0002").statusCode()).isEqualTo(410);
        assertThat(validatedGet("/ctrc0003").statusCode()).isEqualTo(404);
    }

    @Test
    void summaryAndHealthConform() {
        assertThat(validatedGet("/api/v1/analytics/summary").statusCode()).isEqualTo(200);
        assertThat(given().filter(CONTRACT.restAssuredFilter()).queryParam("code", "ctrc0001").get("/api/v1/analytics/summary")
                .statusCode()).isEqualTo(200);
        // Open contract item (recorded for the T050 checkpoint): without an Accept header Actuator answers with
        // application/vnd.spring-boot.actuator.v3+json, which the contract does not list; a client asking for JSON
        // receives application/json, which conforms.
        assertThat(given().filter(CONTRACT.restAssuredFilter()).accept(ContentType.JSON).get("/actuator/health").statusCode())
                .isEqualTo(200);
    }

    private Response validatedGet(String path) {
        return given().filter(CONTRACT.restAssuredFilter()).redirects().follow(false).get(path);
    }

    private Response create(String key, String body, boolean viaFilter) {
        var request = given().contentType(ContentType.JSON).header("Idempotency-Key", key).body(body);
        if (viaFilter) {
            request = request.filter(CONTRACT.restAssuredFilter());
        }
        return request.post("/api/v1/links");
    }

    private void insert(String code, Instant expiresAt) {
        jdbc.update("INSERT INTO SHORT_LINK (SHORT_CODE, TARGET_URL, REQUEST_IDENTITY, PAYLOAD_HASH, CREATED_AT, EXPIRES_AT)"
                + " VALUES (?, 'https://example.com/a', ?, ?, ?, ?)", code, "key-" + code, "1".repeat(64),
                Timestamp.from(expiresAt.minus(Duration.ofDays(7))), Timestamp.from(expiresAt));
    }
}
