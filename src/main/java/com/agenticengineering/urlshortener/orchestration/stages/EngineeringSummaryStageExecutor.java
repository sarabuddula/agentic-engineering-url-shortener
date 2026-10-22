package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.PolicyStore;
import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureRecorder;
import com.agenticengineering.urlshortener.orchestration.reliability.MttrCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * N14 engineering summary (T091; FR-038): rationale (decision lineage), artifacts (current versions with hashes), risks
 * and trade-offs (impact regression risks, release blocking reasons, unrecovered failures), validation (real test counts,
 * latest policy outcomes, release recommendation and decision, MTTR, chain state), assumptions (requirement quality
 * report) and limitations (the scope-control limitations L-SC-*, simulated or replayed evidence, a NOT READY outcome).
 * Persisted once per run (ENGINEERING_SUMMARY), also as a SUMMARY artifact with its content hash, and linked in the
 * lineage. Runs on every path, NOT READY included (reporting only).
 */
@Component
public class EngineeringSummaryStageExecutor implements StageExecutor {

    private final JdbcTemplate jdbc;
    private final RequirementStore requirements;
    private final ArtifactStore artifacts;
    private final DecisionLineage lineage;
    private final PolicyStore policies;
    private final ReleaseDecisionStore releases;
    private final FailureRecorder failures;
    private final AuditChain chain;
    private final Path workspace;
    private final ObjectMapper json = new ObjectMapper();

    public EngineeringSummaryStageExecutor(JdbcTemplate jdbc, RequirementStore requirements, ArtifactStore artifacts, DecisionLineage lineage,
            PolicyStore policies, ReleaseDecisionStore releases, FailureRecorder failures, AuditChain chain,
            @Value("${app.workspace-root:.}") String workspace) {
        this.jdbc = jdbc;
        this.requirements = requirements;
        this.artifacts = artifacts;
        this.lineage = lineage;
        this.policies = policies;
        this.releases = releases;
        this.failures = failures;
        this.chain = chain;
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        String runId = context.runId();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("run_id", runId);
        summary.put("rationale", lineage.forRun(runId).stream().map(d -> Map.of("decision", d.decision(),
                "rationale", d.rationale() == null ? "" : d.rationale())).toList());
        summary.put("artifacts", lineage.currentArtifacts(runId));

        List<String> risks = new ArrayList<>();
        artifacts.latest(runId, "IMPACT_ANALYSIS").ifPresent(a -> read(a.content()).path("regression_risks").forEach(r -> risks.add(r.asText())));
        var release = releases.find(runId);
        release.ifPresent(r -> risks.addAll(r.blockingReasons()));
        var mttr = MttrCalculator.compute(failures.forRun(runId));
        if (mttr.unrecoveredCount() > 0) {
            risks.add(mttr.unrecoveredCount() + " unrecovered failure(s) in this run");
        }
        summary.put("risks_and_tradeoffs", risks);

        Map<String, Object> validation = new LinkedHashMap<>();
        Map<String, Integer> tests = new LinkedHashMap<>();
        for (String status : new String[] {"PASSED", "FAILED", "SKIPPED"}) {
            tests.put(status.toLowerCase(), jdbc.queryForObject("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND STATUS = ?",
                    Integer.class, runId, status));
        }
        validation.put("tests", tests);
        validation.put("policy_outcomes", policies.latestOutcomes(runId).stream().map(e -> e.getKey() + " " + e.getValue()).toList());
        validation.put("release_recommendation", release.map(ReleaseDecisionStore.ReleaseDecision::recommendation).orElse("NOT_DETERMINED"));
        validation.put("release_decision", release.map(ReleaseDecisionStore.ReleaseDecision::decision).orElse("NONE"));
        validation.put("mttr", mttr.describe());
        validation.put("chain_intact", chain.verify(runId).intact());
        summary.put("validation", validation);

        Map<String, Object> assumptions = new LinkedHashMap<>();
        requirements.qualityReport(runId).map(this::read).ifPresent(q -> {
            assumptions.put("requirement_kind", q.path("kind").asText());
            assumptions.put("quality_rationale", q.path("rationale").asText());
        });
        summary.put("assumptions", assumptions);
        summary.put("limitations", limitations(runId, release.map(ReleaseDecisionStore.ReleaseDecision::recommendation).orElse(null)));

        String content = json.writeValueAsString(summary);
        jdbc.update("MERGE INTO ENGINEERING_SUMMARY (RUN_ID, CONTENT, CREATED_AT) KEY (RUN_ID) VALUES (?, ?, ?)", runId, content,
                Timestamp.from(Instant.now()));
        ArtifactStore.Artifact stored = artifacts.record(runId, "SUMMARY", content, "DETERMINISTIC", "N14");
        lineage.record(runId, "SUMMARY", AuditEntry.ActorType.SYSTEM, "engineering summary generated (FR-038)",
                "rationale, artifacts, risks, validation, assumptions and limitations of this run", List.of(),
                List.of(stored.id() + "@" + stored.version() + "#" + stored.contentHash()));
        return StageResult.succeeded();
    }

    private List<String> limitations(String runId, String recommendation) throws Exception {
        List<String> limitations = new ArrayList<>();
        Path scope = workspace.resolve("docs/scope-control.md");
        if (Files.exists(scope)) {
            // deferred-task rows: | task | deferred item | what remains | requirement refs, L-SC-nn |
            java.util.regex.Pattern id = java.util.regex.Pattern.compile("L-SC-\\d+");
            for (String line : Files.readAllLines(scope)) {
                java.util.regex.Matcher m = id.matcher(line);
                if (line.startsWith("| T") && m.find()) {
                    String[] cells = line.split("\\|");
                    limitations.add(m.group() + ": deferred " + cells[1].trim() + " (" + cells[2].trim() + ")");
                }
            }
        } else {
            limitations.add("scope-control limitations not readable from " + scope);
        }
        int simulated = jdbc.queryForObject("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = ? AND EVIDENCE_CLASS = 'SIMULATED'"
                + " AND DECISION IN ('APPROVED', 'REJECTED')", Integer.class, runId);
        if (simulated > 0) {
            limitations.add(simulated + " decision(s) were executed by a fixture (evidence class SIMULATED), not by a person");
        }
        String execution = jdbc.queryForObject("SELECT EXECUTION_CLASS FROM WORKFLOW_RUN WHERE ID = ?", String.class, runId);
        if ("REPLAY".equals(execution)) {
            limitations.add("execution class REPLAY: the run exercises behaviour implemented before it");
        }
        if ("NOT_READY".equals(recommendation)) {
            limitations.add("release readiness NOT_READY: release authorization was not offered");
        }
        return limitations;
    }

    private JsonNode read(String content) {
        try {
            return json.readTree(content);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
