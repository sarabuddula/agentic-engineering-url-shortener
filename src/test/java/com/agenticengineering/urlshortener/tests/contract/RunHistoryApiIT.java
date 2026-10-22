package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.tests.support.CompletionEvidenceFixture;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import com.agenticengineering.urlshortener.tests.support.PolicyRunFixture;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * T085 (FR-023, FR-030, PVT-007, US-005): GET /runs/{runId}/history returns the full ordered lineage - transitions,
 * approvals, decisions with rationale, rejected alternatives and artifact refs, terminal outcome and the final chain
 * verification - and the node states can be reconstructed from it alone. Any control-plane role may read it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PolicyRunFixture.class, CompletionEvidenceFixture.class})
class RunHistoryApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private PolicyRunFixture fixture;

    @Autowired
    private CompletionEvidenceFixture completionEvidence;

    @Autowired
    private WorkflowStore store;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void aCompletedRunIsReconstructableFromItsHistory() {
        Map<String, Evidence> evidence = PolicyRunFixture.goodEvidence();
        String runId = fixture.runThroughPolicy(evidence);
        completionEvidence.recordAll(runId);
        fixture.decideRelease(runId, evidence, true);

        Response history = history(runId, "REVIEWER");

        assertThat(history.statusCode()).isEqualTo(200);
        List<Map<String, Object>> entries = history.jsonPath().getList("$");
        for (int i = 0; i < entries.size(); i++) {
            assertThat(((Number) entries.get(i).get("seq")).intValue()).as("ordered, no gaps").isEqualTo(i + 1);
        }
        assertThat(entries).extracting(e -> e.get("action")).contains("RUN_CREATED", "NODE_TRANSITION", "APPROVAL_REQUESTED",
                "APPROVAL_DECIDED", "DECISION_RECORDED", "RUN_TRANSITION");
        assertThat(entries.get(entries.size() - 1)).containsEntry("action", "CHAIN_VERIFIED");
        Map<String, Object> design = entries.stream().filter(e -> "DECISION_RECORDED".equals(e.get("action"))
                && "DESIGN_APPROVAL".equals(e.get("result"))).findFirst().orElseThrow();
        assertThat(design).containsKeys("rationale", "rejected_alternatives", "artifact_refs");
        assertThat((List<?>) design.get("rejected_alternatives")).isNotEmpty();

        Map<String, String> reconstructed = new HashMap<>();
        for (Map<String, Object> e : entries) {
            if ("NODE_TRANSITION".equals(e.get("action"))) {
                String result = (String) e.get("result");
                reconstructed.put((String) e.get("affected"), result.substring(result.indexOf("->") + 2));
            }
        }
        Map<String, NodeStatus> actual = store.nodeStatuses(runId);
        actual.forEach((node, status) -> assertThat(reconstructed.get(node)).as(node).isEqualTo(status.name()));
        assertThat(entries).anyMatch(e -> "RUN_TRANSITION".equals(e.get("action")) && ((String) e.get("result")).endsWith("->COMPLETED"));
    }

    @Test
    void everyControlPlaneRoleMayReadAndUnknownRunsAre404() {
        String runId = fixture.runToDesignGate(PolicyRunFixture.goodEvidence());

        for (String role : List.of("ENGINEER", "APPROVER", "RELEASE_OWNER", "REVIEWER")) {
            assertThat(history(runId, role).statusCode()).as(role).isEqualTo(200);
        }
        assertThat(given().get("/api/v1/orchestrator/runs/" + runId + "/history").statusCode()).isEqualTo(401);
        Response unknown = history("run-does-not-exist", "REVIEWER");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("code")).isEqualTo("RUN_NOT_FOUND");
    }

    private Response history(String runId, String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role))
                .get("/api/v1/orchestrator/runs/" + runId + "/history");
    }
}
