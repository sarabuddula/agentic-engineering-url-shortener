package com.agenticengineering.urlshortener.tests.contract;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T064: GET /api/v1/orchestrator/runs/{runId}/verify (FR-048, ADR-005): REVIEWER only; an intact chain reports
 * verified, head hash and event count; each tamper class (altered, removed, reordered, truncated) is reported with
 * its position and the contract's check name. Runs are created without execution so the chain is stable under tamper.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerifyApiIT {

    private static final OpenApiContractHarness CONTRACT = OpenApiContractHarness.load();

    @LocalServerPort
    private int port;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private AuditChain chain;

    @Autowired
    private JdbcTemplate jdbc;

    private String runId;
    private long events;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        for (int i = 0; i < 3; i++) {
            chain.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "FIXTURE_EVENT", Instant.now(), "N01", "OK", "event " + i));
        }
        events = jdbc.queryForObject("SELECT AUDIT_EVENT_COUNT FROM WORKFLOW_RUN WHERE ID = ?", Long.class, runId);
    }

    @Test
    void anIntactChainVerifiesWithHeadAndCount() {
        Response ok = verify("REVIEWER");

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.jsonPath().getBoolean("verified")).isTrue();
        assertThat(ok.jsonPath().getString("run_id")).isEqualTo(runId);
        assertThat(ok.jsonPath().getLong("event_count")).isEqualTo(events);
        assertThat(ok.jsonPath().getString("head_hash"))
                .isEqualTo(jdbc.queryForObject("SELECT AUDIT_HEAD_HASH FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId));
    }

    @Test
    void anAlteredEventIsAHashMismatchAtItsPosition() {
        jdbc.update("UPDATE AUDIT_EVENT SET REASON = 'rewritten' WHERE RUN_ID = ? AND SEQ = 2", runId);

        assertBroken(2, "hash-mismatch");
    }

    @Test
    void aRemovedEventIsASequenceGap() {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE RUN_ID = ? AND SEQ = 2", runId);

        assertBroken(2, "sequence-gap");
    }

    @Test
    void reorderedEventsBreakTheLink() {
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 99 WHERE RUN_ID = ? AND SEQ = 2", runId);
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 2 WHERE RUN_ID = ? AND SEQ = 3", runId);
        jdbc.update("UPDATE AUDIT_EVENT SET SEQ = 3 WHERE RUN_ID = ? AND SEQ = 99", runId);

        assertBroken(2, "link-broken");
    }

    @Test
    void aTruncatedTailIsDetected() {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE RUN_ID = ? AND SEQ = ?", runId, events);

        assertBroken(events, "truncation");
    }

    @Test
    void onlyReviewersMayVerify() {
        Response engineer = given().header("X-API-Key", DemoKeyFixtures.rawKey("ENGINEER")).get(path());

        assertThat(engineer.statusCode()).isEqualTo(403);
        assertThat(CONTRACT.responseViolations("GET", "/api/v1/orchestrator/runs/r/verify", 403, engineer.asString())).isEmpty();
    }

    @Test
    void anUnknownRunIs404() {
        Response missing = given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey("REVIEWER"))
                .get("/api/v1/orchestrator/runs/run-does-not-exist/verify");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("RUN_NOT_FOUND");
    }

    private void assertBroken(long position, String check) {
        Response broken = verify("REVIEWER");

        assertThat(broken.statusCode()).isEqualTo(200);
        assertThat(broken.jsonPath().getBoolean("verified")).isFalse();
        assertThat(broken.jsonPath().getLong("failed_at_seq")).isEqualTo(position);
        assertThat(broken.jsonPath().getString("check")).isEqualTo(check);
    }

    private Response verify(String role) {
        return given().filter(CONTRACT.restAssuredFilter()).header("X-API-Key", DemoKeyFixtures.rawKey(role)).get(path());
    }

    private String path() {
        return "/api/v1/orchestrator/runs/" + runId + "/verify";
    }
}
