package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * T089 option B (human decision 2026-10-22, approvals/2026-10-22-t089-design-revisions.md): POST
 * /runs/{runId}/design-revisions (ENGINEER) records the next DESIGN version with the revision note, audited; a stage
 * that read the earlier version makes the revision a live replan trigger. Requirements: FR-029, SC-003.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DesignRevisionApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void anEngineerRevisesTheDesignAndTheRevisionTriggersAReplan() {
        String runId = engine.createRun("api:demo-engineer", "POL-2026-10-18-v1");
        artifacts.record(runId, "DESIGN", "{\"tasks\":{\"t1\":\"x\"}}", "DETERMINISTIC", "N06");
        artifacts.read(runId, "DESIGN", "N08");

        Response revised = revise(runId, "ENGINEER", "record that the status endpoint is not rate-limited");

        assertThat(revised.statusCode()).isEqualTo(201);
        assertThat(revised.jsonPath().getString("artifact_type")).isEqualTo("DESIGN");
        assertThat(revised.jsonPath().getInt("version")).isEqualTo(2);
        ArtifactStore.Artifact latest = artifacts.latest(runId, "DESIGN").orElseThrow();
        assertThat(latest.provenance()).isEqualTo("AI_ASSISTED_HUMAN_REVIEWED");
        assertThat(latest.content()).contains("\"tasks\"").contains("record that the status endpoint is not rate-limited");
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("DESIGN_REVISED") && e.reason().contains("demo-engineer"));

        assertThat(given().header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER")).post("/api/v1/orchestrator/runs/" + runId + "/replan")
                .statusCode()).isEqualTo(202);
    }

    @Test
    void rolesNotesAndMissingDesignsAreChecked() {
        String runId = engine.createRun("api:demo-engineer", "POL-2026-10-18-v1");

        assertThat(revise(runId, "ENGINEER", "a note").statusCode()).isEqualTo(409);
        assertThat(revise(runId, "ENGINEER", "a note").jsonPath().getString("code")).isEqualTo("DESIGN_NOT_FOUND");
        artifacts.record(runId, "DESIGN", "{}", "DETERMINISTIC", "N06");
        assertThat(revise(runId, "ENGINEER", "  ").statusCode()).isEqualTo(400);
        assertThat(revise(runId, "REVIEWER", "a note").statusCode()).isEqualTo(403);
        assertThat(revise("run-does-not-exist", "ENGINEER", "a note").statusCode()).isEqualTo(404);
    }

    private Response revise(String runId, String role, String note) {
        return given().filter(CONTRACT.restAssuredFilter()).contentType(ContentType.JSON).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .body(Map.of("revision_note", note)).post("/api/v1/orchestrator/runs/" + runId + "/design-revisions");
    }
}
