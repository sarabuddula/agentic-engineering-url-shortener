package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** T110 API side: POST /runs/{runId}/resume (FR-028, FR-045): roles, 200 on a recorded resumption, 409, 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResumeApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void anEngineerResumesAnUngovernedStop() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        store.safeStopRun(runId, RunStatus.RUNNING, "FAILURE", "fixture: stage failure");

        Response resumed = resume(runId, "ENGINEER");

        assertThat(resumed.statusCode()).isEqualTo(200);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("RUN_RESUMED"));
    }

    @Test
    void rolesConflictsAndUnknownRuns() {
        String running = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        String governed = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        store.safeStopRun(governed, RunStatus.RUNNING, "BOOT_RECOVERY", "fixture: restart");

        assertThat(resume(running, "ENGINEER").statusCode()).isEqualTo(409);
        assertThat(resume(governed, "ENGINEER").statusCode()).as("governed stop needs an APPROVER").isEqualTo(403);
        assertThat(given().header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER")).post("/api/v1/orchestrator/runs/" + running + "/resume")
                .statusCode()).isEqualTo(403);
        assertThat(resume("run-does-not-exist", "ENGINEER").statusCode()).isEqualTo(404);
    }

    private Response resume(String runId, String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .post("/api/v1/orchestrator/runs/" + runId + "/resume");
    }
}
