package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T093 (human decision 2026-10-22, approvals/2026-10-22-t093-g6-requests.md): an ENGINEER requests a G6 accepted
 * limitation; a RELEASE_OWNER other than the requester approves it with the accepted text, which writes the
 * ACCEPTED_LIMITATION row with its approval reference (CL-5). A rejection writes nothing. Requirements: FR-037.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptedLimitationApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void anApprovedRequestWritesTheAcceptedLimitationRow() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        Response requested = request(runId, "ENGINEER", "L-SC-01", "per-key control-plane rate limit deferred", "role-scoped demo keys");
        assertThat(requested.statusCode()).isEqualTo(201);
        assertThat(requested.jsonPath().getString("status")).isEqualTo("PENDING_APPROVAL");
        String approvalId = requested.jsonPath().getString("approval_id");

        Response approved = decide(runId, approvalId, "RELEASE_OWNER", Map.of("decision", "APPROVE",
                "accepted_limitation_text", "accepted for the local demonstration"));
        assertThat(approved.statusCode()).isEqualTo(200);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT DESCRIPTION, COMPENSATING_CONTROL, APPROVAL_ID FROM ACCEPTED_LIMITATION"
                + " WHERE RUN_ID = ?", runId);
        assertThat(rows).hasSize(1);
        assertThat((String) rows.get(0).get("DESCRIPTION")).contains("L-SC-01").contains("per-key control-plane rate limit deferred")
                .contains("accepted for the local demonstration");
        assertThat(rows.get(0)).containsEntry("COMPENSATING_CONTROL", "role-scoped demo keys")
                .containsEntry("APPROVAL_ID", Long.parseLong(approvalId));
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("ACCEPTED_LIMITATION_APPROVED") && e.reason().contains("L-SC-01"));
    }

    @Test
    void rolesFieldsRejectionAndTerminalRunsAreChecked() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        assertThat(request(runId, "REVIEWER", "L-SC-02", "d", "c").statusCode()).isEqualTo(403);
        assertThat(request(runId, "ENGINEER", "L-SC-02", " ", "c").statusCode()).isEqualTo(400);
        assertThat(request(runId, "ENGINEER", "not-an-id", "d", "c").statusCode()).isEqualTo(400);
        assertThat(request("run-does-not-exist", "ENGINEER", "L-SC-02", "d", "c").statusCode()).isEqualTo(404);

        String approvalId = request(runId, "ENGINEER", "L-SC-02", "d", "c").jsonPath().getString("approval_id");
        assertThat(decide(runId, approvalId, "APPROVER", Map.of("decision", "APPROVE", "accepted_limitation_text", "x")).statusCode())
                .isEqualTo(403);
        assertThat(decide(runId, approvalId, "RELEASE_OWNER", Map.of("decision", "REJECT", "reason", "not accepted")).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACCEPTED_LIMITATION WHERE RUN_ID = ?", Integer.class, runId)).isZero();

        jdbc.update("UPDATE WORKFLOW_RUN SET STATUS = 'COMPLETED' WHERE ID = ?", runId);
        Response terminal = request(runId, "ENGINEER", "L-SC-03", "d", "c");
        assertThat(terminal.statusCode()).isEqualTo(409);
        assertThat(terminal.jsonPath().getString("code")).isEqualTo("LIMITATION_CONFLICT");
    }

    private Response request(String runId, String role, String limitationId, String description, String control) {
        return given().filter(CONTRACT.restAssuredFilter()).contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .body(Map.of("limitation_id", limitationId, "description", description, "compensating_control", control))
                .post("/api/v1/orchestrator/runs/" + runId + "/accepted-limitations");
    }

    private Response decide(String runId, String approvalId, String role, Map<String, String> body) {
        return given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role)).body(body)
                .post("/api/v1/orchestrator/runs/" + runId + "/approvals/" + approvalId);
    }
}
