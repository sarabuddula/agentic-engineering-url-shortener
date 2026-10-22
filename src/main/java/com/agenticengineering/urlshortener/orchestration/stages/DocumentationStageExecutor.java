package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * N10 documentation (plan section 3; whitelist AR, AW): a DOC artifact recording, from the CODE artifacts of the run,
 * every commit and changed file, which of them are documentation (.md, .yaml, .yml), and the design it implements.
 */
public final class DocumentationStageExecutor implements StageExecutor {

    private final ArtifactStore artifacts;
    private final ObjectMapper json = new ObjectMapper();

    private DocumentationStageExecutor(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        String runId = context.runId();
        List<ArtifactStore.Artifact> code = artifacts.all(runId, "CODE");
        if (code.isEmpty()) {
            return StageResult.failed("permanent: no implementation recorded to document");
        }
        TreeSet<String> commits = new TreeSet<>();
        TreeSet<String> files = new TreeSet<>();
        for (ArtifactStore.Artifact a : code) {
            JsonNode c = json.readTree(a.content());
            commits.add(c.path("commit").asText());
            c.path("files").forEach(f -> files.add(f.asText()));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        artifacts.latest(runId, "DESIGN").ifPresent(d -> doc.put("design", d.id() + "@" + d.version() + "#" + d.contentHash()));
        doc.put("commits", commits);
        doc.put("changed_files", files);
        doc.put("documentation_files", files.stream().filter(f -> f.endsWith(".md") || f.endsWith(".yaml") || f.endsWith(".yml")).toList());
        artifacts.record(runId, "DOC", json.writeValueAsString(doc), "DETERMINISTIC", "N10");
        return StageResult.succeeded();
    }

    /** Builds documentation executors (the workspace is read through the CODE artifacts). */
    @Component
    public static class Factory {

        private final ArtifactStore artifacts;

        public Factory(ArtifactStore artifacts, @Value("${app.workspace-root:.}") String workspace) {
            this.artifacts = artifacts;
        }

        public DocumentationStageExecutor forWorkspace(Path root) {
            return new DocumentationStageExecutor(artifacts);
        }

        public DocumentationStageExecutor production() {
            return new DocumentationStageExecutor(artifacts);
        }
    }
}
