package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
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
 * T081: GET /api/v1/orchestrator/runs/{runId}/release-readiness (FR-037): RELEASE_OWNER or REVIEWER; recommendation,
 * one outcome per policy check, blocking reasons when NOT READY, and the human decision. 404 for an unknown run and
 * for a run that has no determination yet.
 * Requirements: US-004 (release-readiness determination).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PolicyRunFixture.class)
class ReleaseReadinessApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private OrchestrationEngine engine;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void aReadyRunShowsEveryCheckAndAPendingDecision() {
        String runId = fixture.runThroughPolicy(PolicyRunFixture.goodEvidence());

        Response ready = get(runId, "RELEASE_OWNER");

        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.jsonPath().getString("recommendation")).isEqualTo("READY");
        assertThat(ready.jsonPath().getString("decision")).isEqualTo("PENDING");
        List<Map<String, Object>> outcomes = ready.jsonPath().getList("policy_outcomes");
        assertThat(outcomes).hasSize(16).extracting(o -> o.get("check_id")).doesNotHaveDuplicates();
        assertThat(outcomes).extracting(o -> o.get("outcome")).containsOnly("PASS");
    }

    @Test
    void aNotReadyRunListsItsBlockingReasonsToReviewers() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        evidence.put("DEP-01", Evidence.notOk("pom.xml: com.example:undeclared-lib not in docs/compliance/licenses.xml"));
        String runId = fixture.runThroughPolicy(evidence);

        Response notReady = get(runId, "REVIEWER");

        assertThat(notReady.statusCode()).isEqualTo(200);
        assertThat(notReady.jsonPath().getString("recommendation")).isEqualTo("NOT_READY");
        assertThat(notReady.jsonPath().getList("blocking_reasons", String.class)).anyMatch(r -> r.startsWith("DEP-01"));
    }

    @Test
    void engineersAreForbidden() {
        String runId = fixture.runThroughPolicy(PolicyRunFixture.goodEvidence());

        Response forbidden = given().header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER"))
                .get("/api/v1/orchestrator/runs/" + runId + "/release-readiness");

        assertThat(forbidden.statusCode()).isEqualTo(403);
    }

    @Test
    void unknownRunsAndUndeterminedRunsAre404() {
        String undetermined = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        Response unknown = get("run-does-not-exist", "REVIEWER");
        Response early = get(undetermined, "REVIEWER");

        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("code")).isEqualTo("RUN_NOT_FOUND");
        assertThat(early.statusCode()).isEqualTo(404);
        assertThat(early.jsonPath().getString("code")).isEqualTo("RELEASE_NOT_DETERMINED");
    }

    private Response get(String runId, String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .get("/api/v1/orchestrator/runs/" + runId + "/release-readiness");
    }
}
