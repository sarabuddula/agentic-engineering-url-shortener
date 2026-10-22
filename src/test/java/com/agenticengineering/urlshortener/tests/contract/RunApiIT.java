package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T058: POST /api/v1/orchestrator/runs and GET /runs/{runId} (FR-020, FR-045; contract roles). Keys are the test-only
 * fixture keys loaded from fixtures/demo-keys.test.yml. 401 for a missing or invalid key and 403 for a missing role,
 * both audited on the platform chain by key ID only; creation runs N01 (requirement ingestion).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();
    private static final String SCN_A = "{\"requirement_text\":\"Link status lookup returning whether a short code is active,"
            + " expired or unknown, with its expiry time\"}";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void engineerCreatesARunAndN01IngestsTheRequirement() {
        Response created = given().filter(CONTRACT.restAssuredFilter()).contentType(ContentType.JSON)
                .header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER")).body(SCN_A).post("/api/v1/orchestrator/runs");

        assertThat(created.statusCode()).isEqualTo(201);
        String runId = created.jsonPath().getString("run_id");
        assertThat(created.jsonPath().getString("status")).isEqualTo("RUNNING");
        assertThat(created.jsonPath().getString("policy_version")).isEqualTo("POL-2026-10-18-v1");
        assertThat(jdbc.queryForObject("SELECT CONTENT FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ?", String.class, runId))
                .startsWith("Link status lookup returning");
        assertThat(jdbc.queryForObject("SELECT STATUS FROM STAGE_NODE WHERE RUN_ID = ? AND NODE_KEY = 'N01'", String.class, runId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT REQUIREMENT_REF FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId))
                .isEqualTo("api:test-engineer");
    }

    @Test
    void anyRoleReadsTheRunDetail() {
        String runId = create("ENGINEER", SCN_A).jsonPath().getString("run_id");

        for (String role : List.of("ENGINEER", "APPROVER", "RELEASE_OWNER", "REVIEWER")) {
            Response detail = given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                    .get("/api/v1/orchestrator/runs/" + runId);

            assertThat(detail.statusCode()).as(role).isEqualTo(200);
            assertThat(detail.jsonPath().getString("run_id")).isEqualTo(runId);
            assertThat(detail.jsonPath().getInt("run_revision")).isZero();
            assertThat(detail.jsonPath().getString("execution_class")).isEqualTo("LIVE");
            // the run starts on its own and may already wait at a gate; the list is present either way
            assertThat(detail.jsonPath().getList("pending_approvals")).isNotNull();
        }
    }

    @Test
    void replayExecutionClassIsRecorded() {
        String runId = create("ENGINEER", "{\"requirement_text\":\"Change the default link TTL from 7 days to 24 hours.\","
                + "\"execution_class\":\"REPLAY\"}").jsonPath().getString("run_id");

        assertThat(jdbc.queryForObject("SELECT EXECUTION_CLASS FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId)).isEqualTo("REPLAY");
    }

    @Test
    void missingOrInvalidKeyIs401AndAudited() {
        long before = platformEvents("AUTH_FAILED");
        Response missing = given().contentType(ContentType.JSON).body(SCN_A).post("/api/v1/orchestrator/runs");
        Response invalid = given().contentType(ContentType.JSON).header("X-API-Key", "not-a-key").body(SCN_A)
                .post("/api/v1/orchestrator/runs");

        for (Response response : List.of(missing, invalid)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
            assertThat(CONTRACT.responseViolations("POST", "/api/v1/orchestrator/runs", 401, response.asString())).isEmpty();
        }
        assertThat(platformEvents("AUTH_FAILED")).isEqualTo(before + 2);
        assertThat(jdbc.queryForList("SELECT REASON FROM PLATFORM_AUDIT_EVENT WHERE ACTION = 'AUTH_FAILED'", String.class))
                .noneMatch(r -> r.contains("not-a-key"));
    }

    @Test
    void wrongRoleIs403AndAuditedWithTheKeyId() {
        Response forbidden = create("REVIEWER", SCN_A);

        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.jsonPath().getString("code")).isEqualTo("FORBIDDEN");
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/orchestrator/runs", 403, forbidden.asString())).isEmpty();
        assertThat(jdbc.queryForList("SELECT REASON FROM PLATFORM_AUDIT_EVENT WHERE ACTION = 'ACCESS_DENIED'", String.class))
                .anyMatch(r -> r.contains("test-reviewer") && r.contains("ENGINEER"));
    }

    @Test
    void blankRequirementIs400AndUnknownRunIs404() {
        assertThat(create("ENGINEER", "{\"requirement_text\":\"   \"}").statusCode()).isEqualTo(400);
        Response unknown = given().header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER")).get("/api/v1/orchestrator/runs/run-none");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("code")).isEqualTo("RUN_NOT_FOUND");
    }

    private static Response create(String role, String body) {
        return given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role)).body(body)
                .post("/api/v1/orchestrator/runs");
    }

    private long platformEvents(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM PLATFORM_AUDIT_EVENT WHERE ACTION = ?", Long.class, action);
    }
}
