package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fifth governed change (2026-10-21, T066 c-d): POST /runs/{runId}/policy-exceptions (ENGINEER) opens a G5 approval;
 * a RELEASE_OWNER approving it through the approvals endpoint with the policy_exception payload records the
 * exception's approving authority, compensating control, approved_at and ISO-8601 expiry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PolicyExceptionApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrchestrationEngine engine;

    private String runId;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        // created without starting it, so no stage opens approvals of its own while the test counts them
        runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
    }

    @Test
    void anEngineerRequestsAnExceptionAndAG5ApprovalOpens() {
        Response created = request("ENGINEER", "{\"policy_check_id\":\"DEP-01\",\"reason\":\"license review pending\",\"scope\":\"this run\"}", true);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.jsonPath().getString("status")).isEqualTo("PENDING_APPROVAL");
        long approvalId = Long.parseLong(created.jsonPath().getString("approval_id"));
        assertThat(approvals.find(runId, approvalId).kind()).isEqualTo(ApprovalKind.POLICY_EXCEPTION);
    }

    @Test
    void requestRulesAreEnforced() {
        assertThat(request("APPROVER", "{\"policy_check_id\":\"DEP-01\",\"reason\":\"r\",\"scope\":\"s\"}", false).statusCode())
                .as("ENGINEER only").isEqualTo(403);
        Response unknownCheck = request("ENGINEER", "{\"policy_check_id\":\"XYZ-99\",\"reason\":\"r\",\"scope\":\"s\"}", false);
        assertThat(unknownCheck.statusCode()).isEqualTo(400);
        assertThat(unknownCheck.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
        assertThat(request("ENGINEER", "{\"policy_check_id\":\"DEP-01\",\"scope\":\"s\"}", false).statusCode()).as("reason required")
                .isEqualTo(400);
    }

    @Test
    void approvingThroughTheApprovalsEndpointRecordsTheAuthorityFields() {
        Response created = request("ENGINEER", "{\"policy_check_id\":\"DEP-01\",\"reason\":\"license review pending\",\"scope\":\"this run\"}", true);
        String approvalId = created.jsonPath().getString("approval_id");
        String exceptionId = created.jsonPath().getString("exception_id");
        Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        Response decided = decide(approvalId, "{\"decision\":\"APPROVE\",\"policy_exception\":{\"policy_check_id\":\"DEP-01\","
                + "\"scope\":\"this run\",\"compensating_control\":\"pinned version, no network use\",\"expiry_or_review_condition\":\""
                + expiry + "\"}}");

        assertThat(decided.statusCode()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM POLICY_EXCEPTION WHERE ID = ?", Long.parseLong(exceptionId));
        assertThat(row).containsEntry("APPROVING_AUTHORITY_KEY_ID", "test-release-owner")
                .containsEntry("COMPENSATING_CONTROL", "pinned version, no network use");
        assertThat(row.get("APPROVED_AT")).isNotNull();
        assertThat(((java.time.OffsetDateTime) row.get("EXPIRES_AT")).toInstant()).isEqualTo(expiry);
    }

    @Test
    void anUnparseableOrPastExpiryOrAMismatchedCheckIsRejectedAndLeavesItPending() {
        String approvalId = request("ENGINEER", "{\"policy_check_id\":\"DEP-01\",\"reason\":\"r\",\"scope\":\"this run\"}", true)
                .jsonPath().getString("approval_id");
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        assertThat(decide(approvalId, payload("DEP-01", "next sprint")).statusCode()).isEqualTo(400);
        assertThat(decide(approvalId, payload("DEP-01", Instant.now().minusSeconds(60).toString())).statusCode()).isEqualTo(400);
        assertThat(decide(approvalId, payload("SEC-01", future)).statusCode()).isEqualTo(400);
        assertThat(approvals.find(runId, Long.parseLong(approvalId)).status()).isEqualTo(ApprovalService.Decision.PENDING);
    }

    private static String payload(String check, String expiry) {
        return "{\"decision\":\"APPROVE\",\"policy_exception\":{\"policy_check_id\":\"" + check + "\",\"scope\":\"this run\","
                + "\"compensating_control\":\"control\",\"expiry_or_review_condition\":\"" + expiry + "\"}}";
    }

    private Response request(String role, String body, boolean viaFilter) {
        var request = given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role)).body(body);
        if (viaFilter) {
            request = request.filter(CONTRACT.restAssuredFilter());
        }
        return request.post("/api/v1/orchestrator/runs/" + runId + "/policy-exceptions");
    }

    private Response decide(String approvalId, String body) {
        return given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey("RELEASE_OWNER")).body(body)
                .post("/api/v1/orchestrator/runs/" + runId + "/approvals/" + approvalId);
    }
}
