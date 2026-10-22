package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.ReplanFixture;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * T071 (API side): POST /runs/{runId}/replan (FR-029): ENGINEER only; 202 when staged; 409 without a body (as the contract
 * defines it) when nothing can be replanned, the refusal audited.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReplanFixture.class)
class ReplanApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private ReplanFixture fixture;

    @Autowired
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void anEngineerStagesAReplanAndTheAcceptanceIsPending() {
        String runId = fixture.runToDesignGate();
        fixture.changeUpstream(runId);

        Response staged = replan(runId, "ENGINEER");

        assertThat(staged.statusCode()).isEqualTo(202);
        Response detail = given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER"))
                .get("/api/v1/orchestrator/runs/" + runId);
        List<Map<String, Object>> pending = detail.jsonPath().getList("pending_approvals");
        assertThat(pending).extracting(p -> p.get("kind")).contains("REPLAN_ACCEPTANCE");
    }

    @Test
    void conflictsAndRolesAreEnforced() {
        String runId = fixture.runToDesignGate();

        Response nothing = replan(runId, "ENGINEER");
        assertThat(nothing.statusCode()).isEqualTo(409);
        assertThat(nothing.asString()).as("the contract's 409 for /replan has no body").isEmpty();
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("REPLAN_REFUSED") && e.reason().contains("no upstream change"));
        assertThat(given().header("X-API-Key", DemoKeyFixtures.rawKey("APPROVER")).post("/api/v1/orchestrator/runs/" + runId + "/replan")
                .statusCode()).isEqualTo(403);
        assertThat(replan("run-does-not-exist", "ENGINEER").statusCode()).isEqualTo(404);
    }

    private Response replan(String runId, String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .post("/api/v1/orchestrator/runs/" + runId + "/replan");
    }
}
