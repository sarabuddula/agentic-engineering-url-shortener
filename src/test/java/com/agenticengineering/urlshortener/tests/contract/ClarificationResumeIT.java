package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Defect found in the SCN-C demonstration (2026-10-22, run-10190a85...): the approved G1 clarification was recorded in
 * the approval and the audit chain but never applied to the requirement, so N05 re-checked the original text, found the
 * same ambiguity and the run safe-stopped. An approved clarification must produce requirement version 2 (CONFIRMED,
 * referencing the approval) and the run must decompose from it and continue to the G2 design approval (plan section 11).
 * Requirements: FR-042, SCN-C (recorded clarification updates the requirement and the run resumes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClarificationResumeIT {

    private static final String ANSWER = "links = the existing URL-shortener short links; support = create (POST /api/v1/links)"
            + " and resolve (GET /{code} redirect)";

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void anApprovedClarificationUpdatesTheRequirementAndTheRunContinues() throws InterruptedException {
        String runId = given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER"))
                .body(Map.of("requirement_text", ScenarioFixtures.scnC())).post("/api/v1/orchestrator/runs").jsonPath().getString("run_id");
        assertThat(awaitNotRunning(runId)).isEqualTo(RunStatus.SUSPENDED_CLARIFICATION);
        long approvalId = approvals.pending(runId).get(0).id();

        given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey("APPROVER"))
                .body(Map.of("decision", "APPROVE", "clarification_answer", ANSWER, "reason", "SCN-C plan section 11"))
                .post("/api/v1/orchestrator/runs/" + runId + "/approvals/" + approvalId).then().statusCode(200);
        Thread.sleep(200);
        RunStatus status = awaitNotRunning(runId);

        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT VERSION, PROVENANCE, CAST(CONTENT AS VARCHAR) AS CONTENT, CAST(ASSUMPTIONS AS VARCHAR) AS ASSUMPTIONS"
                        + " FROM REQUIREMENT_ARTIFACT WHERE RUN_ID = ? ORDER BY VERSION", runId);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(1)).containsEntry("VERSION", 2).containsEntry("PROVENANCE", "CONFIRMED");
        assertThat((String) versions.get(1).get("CONTENT")).startsWith(ScenarioFixtures.scnC()).contains(ANSWER);
        assertThat((String) versions.get(1).get("ASSUMPTIONS")).contains("approval " + approvalId);

        assertThat(status).isNotEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(store.nodeStatuses(runId)).containsEntry("N03", NodeStatus.SUCCEEDED).containsEntry("N05", NodeStatus.SUCCEEDED);
        assertThat(approvals.pending(runId)).extracting(ApprovalService.Approval::kind).containsExactly(ApprovalKind.DESIGN_APPROVAL);
    }

    private RunStatus awaitNotRunning(String runId) throws InterruptedException {
        RunStatus status = store.runStatus(runId);
        for (int i = 0; i < 150 && status == RunStatus.RUNNING; i++) {
            Thread.sleep(100);
            status = store.runStatus(runId);
        }
        return status;
    }
}
