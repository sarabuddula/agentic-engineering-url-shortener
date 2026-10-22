package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * N04 impact analysis (T068; FR-041): a read-only scan of the workspace (N04 whitelist AR, AW) recorded as an
 * IMPACT_ANALYSIS artifact before decomposition and implementation. When no impact can be identified the stage fails
 * permanently, so the change never reaches N08 unreviewed. When the change touches an approved requirement (C-005), N04
 * requests a CHANGE_APPROVAL with the impact analysis and waits (T119); the engine settles it like any gate.
 */
@Component
public class ImpactAnalysisStageExecutor implements StageExecutor {

    private final RequirementStore requirements;
    private final ArtifactStore artifacts;
    private final ImpactAnalyzer analyzer;
    private final ApprovalService approvals;
    private final WorkflowStore store;
    private final ObjectMapper json = new ObjectMapper();

    public ImpactAnalysisStageExecutor(RequirementStore requirements, ArtifactStore artifacts, ApprovalService approvals,
            WorkflowStore store, @Value("${app.workspace-root:.}") String workspace) {
        this.approvals = approvals;
        this.store = store;
        this.requirements = requirements;
        this.artifacts = artifacts;
        // a String, not a Path: Spring's Path conversion resolves relative values against the classpath
        this.analyzer = new ImpactAnalyzer(Path.of(workspace).toAbsolutePath().normalize(), ImpactAnalyzer.defaultSeeds());
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        Optional<Map<String, Object>> report = analyzer.analyze(requirements.content(context.runId()));
        if (report.isEmpty()) {
            return StageResult.failed("permanent: impact could not be identified for this change request (no seed matched);"
                    + " human impact review required before implementation (FR-041)");
        }
        ArtifactStore.Artifact impact = artifacts.record(context.runId(), "IMPACT_ANALYSIS", json.writeValueAsString(report.get()),
                "DETERMINISTIC", "N04");
        Object approved = report.get().get("approved_requirement");
        if (approved == null) {
            return StageResult.succeeded();
        }
        ApprovalService.Approval change = approvals.request(context.runId(), ApprovalKind.CHANGE_APPROVAL, "N04",
                store.requesterKeyId(context.runId()), "C-005 change to approved " + approved + " (" + report.get().get("concept")
                        + "); impact analysis " + impact.id() + "@" + impact.version() + "#" + impact.contentHash());
        return StageResult.awaitingApproval("approval " + change.id() + " (CHANGE_APPROVAL) requested for " + approved);
    }
}
