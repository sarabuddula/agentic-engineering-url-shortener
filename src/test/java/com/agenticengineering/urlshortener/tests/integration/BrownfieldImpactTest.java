package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutorRegistry;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.stages.RequirementIngestion;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T067 (FR-041, SCN-B): a change request against the existing shortener produces an impact identification artifact
 * (components, interfaces, data flows, tests, documentation, regression risks, rollout and rollback) at N04, stored with
 * its content hash, before any implementation work. N01, N02, N04, N05 and the N07 gate are the production executors;
 * stages not built yet are labelled fixtures.
 * Requirements: US-003 (brownfield change).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BrownfieldImpactTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private RequirementIngestion ingestion;

    @Autowired
    private StageExecutorRegistry registry;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private WorkflowStore store;

    @Test
    void aChangeRequestProducesAHashedImpactArtifact() throws Exception {
        String runId = start(ScenarioFixtures.scnB());

        // SCN-B changes the approved A-006 default, so N04 waits on a CHANGE_APPROVAL after recording the impact (T119)
        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.AWAITING_APPROVAL);
        ArtifactStore.Artifact impact = artifacts.latest(runId, "IMPACT_ANALYSIS").orElseThrow();
        assertThat(impact.provenance()).isEqualTo("DETERMINISTIC");
        assertThat(impact.contentHash()).isEqualTo(sha256(impact.content()));

        JsonNode report = JSON.readTree(impact.content());
        assertThat(texts(report, "components")).anyMatch(c -> c.endsWith("shortener/domain/LinkService.java"))
                .anyMatch(c -> c.endsWith("shortener/config/ShortenerProperties.java"));
        assertThat(texts(report, "interfaces")).anyMatch(i -> i.contains("app.default-ttl-days"))
                .anyMatch(i -> i.contains("POST /api/v1/links"));
        assertThat(texts(report, "data_flows")).anyMatch(d -> d.contains("SHORT_LINK.EXPIRES_AT"));
        assertThat(texts(report, "tests")).anyMatch(t -> t.endsWith("ConfigurationBindingTest.java"));
        assertThat(texts(report, "documentation")).anyMatch(d -> d.endsWith("spec.md")).anyMatch(d -> d.endsWith("openapi.yaml"));
        assertThat(texts(report, "regression_risks")).isNotEmpty();
        assertThat(texts(report, "rollout")).isNotEmpty();
        assertThat(texts(report, "rollback")).isNotEmpty();
    }

    @Test
    void impactIdentificationPrecedesImplementationInTheLineage() {
        String runId = start(ScenarioFixtures.scnB());
        approveAndResume(runId, "change approved"); // CHANGE_APPROVAL at N04 (T119)
        approveAndResume(runId, "design approved"); // DESIGN_APPROVAL at N07

        List<WorkflowStore.HistoryEntry> history = store.history(runId);
        long recorded = history.stream().filter(e -> e.action().equals("ARTIFACT_RECORDED") && e.reason().contains("IMPACT_ANALYSIS"))
                .findFirst().orElseThrow().seq();
        long implementation = history.stream().filter(e -> e.action().equals("NODE_TRANSITION") && "N08".equals(e.affected())
                && e.result().endsWith("->RUNNING")).findFirst().orElseThrow().seq();
        assertThat(recorded).isLessThan(implementation);
    }

    @Test
    void aGreenfieldRequirementTakesNoImpactBranch() {
        String runId = start(ScenarioFixtures.scnA());

        assertThat(store.nodeStatuses(runId)).containsEntry("N04", NodeStatus.NOT_APPLICABLE);
        assertThat(artifacts.latest(runId, "IMPACT_ANALYSIS")).isEmpty();
    }

    @Test
    void aChangeWhoseImpactCannotBeIdentifiedStopsBeforeImplementation() {
        String runId = start("Change the redirect status code from 302 to 301.");

        assertThat(store.nodeStatuses(runId)).containsEntry("N03", NodeStatus.NOT_APPLICABLE).containsEntry("N08", NodeStatus.PENDING);
        assertThat(store.nodeStatuses(runId)).as("a failed node is safe-stopped (FP-4)").containsEntry("N04", NodeStatus.SAFE_STOPPED);
        assertThat(store.runStatus(runId)).isEqualTo(RunStatus.SAFE_STOPPED);
        assertThat(store.history(runId)).anyMatch(e -> "N04".equals(e.affected()) && e.reason().contains("impact could not be identified"));
        assertThat(artifacts.latest(runId, "IMPACT_ANALYSIS")).isEmpty();
    }

    private void approveAndResume(String runId, String reason) {
        ApprovalService.Approval pending = approvals.pending(runId).get(0);
        approvals.decide(runId, pending.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true, reason,
                ApprovalService.EvidenceClass.SIMULATED);
        engine.resume(runId, executors(), task -> StageResult.succeeded());
    }

    private String start(String requirement) {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ingestion.ingest(runId, RequirementIngestion.normalize(requirement));
        engine.execute(runId, executors(), task -> StageResult.succeeded());
        return runId;
    }

    /** Production executors; the stages not built yet (N06, N09, N10, N11, N14) are labelled fixtures. */
    private Map<String, StageExecutor> executors() {
        Map<String, StageExecutor> executors = registry.executors();
        for (String node : List.of("N06", "N09", "N10", "N11", "N14")) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        return executors;
    }

    private static List<String> texts(JsonNode report, String field) {
        JsonNode array = Optional.ofNullable(report.get(field)).orElseThrow(() -> new AssertionError("missing section " + field));
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
