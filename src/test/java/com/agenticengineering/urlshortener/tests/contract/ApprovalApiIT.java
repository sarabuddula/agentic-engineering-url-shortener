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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T061 (API side): POST /runs/{runId}/approvals/{approvalId} and pending approvals in the run detail (FR-024, FR-046;
 * ADR-008 roles). Approvals are requested through the service on an API-created run so each case is isolated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalApiIT {

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
    void pendingApprovalsAppearInTheRunDetail() {
        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        Response detail = given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER"))
                .get("/api/v1/orchestrator/runs/" + runId);

        List<Map<String, Object>> pending = detail.jsonPath().getList("pending_approvals");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0)).containsEntry("approval_id", String.valueOf(approval.id())).containsEntry("kind", "DESIGN_APPROVAL")
                .containsEntry("gate", "G2").containsKeys("requested_at", "expires_at", "reason");
    }

    @Test
    void aDistinctApproverApprovesWith200AndThePendingListEmpties() {
        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        Response decided = decide("APPROVER", approval.id(), "{\"decision\":\"APPROVE\",\"reason\":\"ADRs reviewed\"}", true);

        assertThat(decided.statusCode()).isEqualTo(200);
        assertThat(approvals.find(runId, approval.id()).status()).isEqualTo(ApprovalService.Decision.APPROVED);
        assertThat(approvals.pending(runId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT EVIDENCE_CLASS FROM APPROVAL WHERE ID = ?", String.class, approval.id())).isEqualTo("HUMAN");
    }

    @Test
    void rejectionIsRecorded() {
        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        Response decided = decide("APPROVER", approval.id(), "{\"decision\":\"REJECT\",\"reason\":\"design does not satisfy FR-012\"}", true);

        assertThat(decided.statusCode()).isEqualTo(200);
        assertThat(approvals.find(runId, approval.id()).status()).isEqualTo(ApprovalService.Decision.REJECTED);
    }

    @Test
    void selfApprovalIs403SelfApprovalDeniedAndStaysPending() {
        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-approver", "own request");

        Response denied = decide("APPROVER", approval.id(), "{\"decision\":\"APPROVE\"}", false);

        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.jsonPath().getString("code")).isEqualTo("SELF_APPROVAL_DENIED");
        assertThat(CONTRACT.responseViolations("POST", "/api/v1/orchestrator/runs/r/approvals/1", 403, denied.asString())).isEmpty();
        assertThat(approvals.pending(runId)).hasSize(1);
    }

    @Test
    void endpointAndGateRolesAreEnforced() {
        ApprovalService.Approval design = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        assertThat(decide("ENGINEER", design.id(), "{\"decision\":\"APPROVE\"}", false).statusCode()).as("endpoint role").isEqualTo(403);
        assertThat(decide("RELEASE_OWNER", design.id(), "{\"decision\":\"APPROVE\"}", false).statusCode()).as("gate role").isEqualTo(403);
        assertThat(approvals.pending(runId)).hasSize(1);
    }

    @Test
    void clarificationApprovalNeedsAnAnswer() {
        ApprovalService.Approval clarification = approvals.request(runId, ApprovalKind.CLARIFICATION, "N03", "test-engineer", "ambiguous");

        Response missing = decide("APPROVER", clarification.id(), "{\"decision\":\"APPROVE\"}", false);
        Response answered = decide("APPROVER", clarification.id(),
                "{\"decision\":\"APPROVE\",\"clarification_answer\":\"links = short links; support = create + resolve\"}", true);

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("INVALID_REQUEST");
        assertThat(answered.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT REASON FROM APPROVAL WHERE ID = ?", String.class, clarification.id()))
                .contains("links = short links");
    }

    @Test
    void anExpiredApprovalIs410() {
        ApprovalService.Approval approval = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");
        jdbc.update("UPDATE APPROVAL SET DECISION = 'ESCALATED', TIMEOUT_AT = DATEADD('MINUTE', -20, CURRENT_TIMESTAMP),"
                + " SECOND_WINDOW_TIMEOUT_AT = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) WHERE ID = ?", approval.id());

        Response late = decide("APPROVER", approval.id(), "{\"decision\":\"APPROVE\"}", false);

        assertThat(late.statusCode()).isEqualTo(410);
        assertThat(late.jsonPath().getString("code")).isEqualTo("APPROVAL_EXPIRED");
    }

    @Test
    void unknownApprovalIs404() {
        Response unknown = decide("APPROVER", 999_999L, "{\"decision\":\"APPROVE\"}", false);

        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("code")).isEqualTo("APPROVAL_NOT_FOUND");
    }

    private Response decide(String role, long approvalId, String body, boolean viaFilter) {
        var request = given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role)).body(body);
        if (viaFilter) {
            request = request.filter(CONTRACT.restAssuredFilter());
        }
        return request.post("/api/v1/orchestrator/runs/" + runId + "/approvals/" + approvalId);
    }
}
