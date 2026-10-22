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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Defect found in the SCN-C demonstration (2026-10-22): POST /runs ingested the requirement but never started the run,
 * so it stayed RUNNING at N01. A created run must proceed on its own to its first gate - for SCN-C ("Add support for
 * links.") the quality check finds material ambiguity and the run suspends at the G1 clarification.
 * Requirements: FR-042, SCN-C (ambiguity blocks implementation and opens the G1 clarification).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunStartIT {

    @LocalServerPort
    private int port;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private ApprovalService approvals;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void aCreatedRunProceedsToItsFirstGate() throws InterruptedException {
        String runId = given().contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER"))
                .body(Map.of("requirement_text", ScenarioFixtures.scnC())).post("/api/v1/orchestrator/runs").jsonPath().getString("run_id");

        RunStatus status = RunStatus.RUNNING;
        for (int i = 0; i < 100 && status == RunStatus.RUNNING; i++) {
            Thread.sleep(100);
            status = store.runStatus(runId);
        }

        assertThat(status).isEqualTo(RunStatus.SUSPENDED_CLARIFICATION);
        assertThat(store.nodeStatuses(runId)).containsEntry("N02", NodeStatus.SUCCEEDED).containsEntry("N03", NodeStatus.AWAITING_APPROVAL);
        assertThat(approvals.pending(runId)).extracting(ApprovalService.Approval::kind).containsExactly(ApprovalKind.CLARIFICATION);
    }
}
