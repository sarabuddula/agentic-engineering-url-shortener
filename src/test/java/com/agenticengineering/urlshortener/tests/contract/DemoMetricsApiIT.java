package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * T083 and T104 (FR-031, C-004, PVT-008, SC-005): GET /api/v1/metrics/demo (REVIEWER) is always labelled DEMONSTRATION
 * and reports success rate, retry/rollback/compensation counts, MTTR (primary, governance wait excluded; null without a
 * recovered failure), the secondary MTTR including governance wait, recovered/unrecovered counts, governance wait and
 * mean end-to-end latency (run start to terminal transition). The context may be shared with other tests, so counts are
 * checked as deltas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.orchestration.retry.base-delay-ms=20")
class DemoMetricsApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private GateExecutor.Factory gates;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void aRecoveredRetryShowsUpInTheLabelledMetrics() {
        JsonPath before = metrics("REVIEWER").jsonPath();
        AtomicInteger calls = new AtomicInteger();
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", ctx -> calls.incrementAndGet() < 2 ? StageResult.failed("transient: storage timeout") : StageResult.succeeded());
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        // end the run (release rejected at G7) so it has a terminal transition for the end-to-end latency
        ApprovalService.Approval release = approvals.pending(runId).get(0);
        approvals.decide(runId, release.id(), "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, AuditEntry.ActorType.HUMAN, false,
                "rejected", ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors, task -> StageResult.succeeded());

        Response after = metrics("REVIEWER");

        assertThat(after.statusCode()).isEqualTo(200);
        JsonPath now = after.jsonPath();
        assertThat(now.getString("measurement_class")).isEqualTo("DEMONSTRATION");
        assertThat(now.getInt("retry_count")).isEqualTo(before.getInt("retry_count") + 1);
        assertThat(now.getInt("recovered_failure_events")).isEqualTo(before.getInt("recovered_failure_events") + 1);
        assertThat(now.getDouble("mttr_ms")).isPositive();
        assertThat(now.getDouble("mttr_incl_governance_wait_ms")).isGreaterThanOrEqualTo(now.getDouble("mttr_ms"));
        assertThat(now.getDouble("workflow_success_rate")).isBetween(0.0, 1.0);
        assertThat(now.getDouble("end_to_end_latency_ms")).isPositive();
        assertThat(now.getInt("compensation_count")).isGreaterThanOrEqualTo(0);
        assertThat(now.getInt("rollback_count")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void onlyReviewersReadTheMetrics() {
        assertThat(given().header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER")).get("/api/v1/metrics/demo").statusCode()).isEqualTo(403);
    }

    private Response metrics(String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role)).get("/api/v1/metrics/demo");
    }

    private Map<String, StageExecutor> fixtures() {
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(Set.of()));
        executors.put("N05", ctx -> {
            if (tasks.statuses(ctx.runId()).isEmpty()) {
                tasks.saveDecomposition(ctx.runId(), List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of())));
            }
            return StageResult.succeeded();
        });
        executors.put("N12", ctx -> StageResult.succeeded(Set.of("N13")));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        executors.remove("N08");
        return executors;
    }
}
