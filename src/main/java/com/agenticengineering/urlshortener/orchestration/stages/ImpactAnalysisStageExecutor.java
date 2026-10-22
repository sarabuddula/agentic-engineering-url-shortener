package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * N04 impact analysis (T068; FR-041): a read-only scan of the workspace (N04 whitelist AR, AW) recorded as an
 * IMPACT_ANALYSIS artifact before decomposition and implementation. When no impact can be identified the stage fails
 * permanently, so the change never reaches N08 unreviewed.
 */
@Component
public class ImpactAnalysisStageExecutor implements StageExecutor {

    private final RequirementStore requirements;
    private final ArtifactStore artifacts;
    private final ImpactAnalyzer analyzer;
    private final ObjectMapper json = new ObjectMapper();

    public ImpactAnalysisStageExecutor(RequirementStore requirements, ArtifactStore artifacts,
            @Value("${app.workspace-root:.}") String workspace) {
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
        artifacts.record(context.runId(), "IMPACT_ANALYSIS", json.writeValueAsString(report.get()), "DETERMINISTIC", "N04");
        return StageResult.succeeded();
    }
}
