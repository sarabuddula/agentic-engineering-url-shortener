package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * N06 design (plan section 3; whitelist AR, AW): a deterministic DESIGN artifact from the run's own records - the
 * requirement and its quality report (kind, rationale), the decomposed tasks, and the impact analysis when there is one
 * (read through ArtifactStore.read, so a replan re-validates it). Acceptance criteria are derived per task. The design
 * is then reviewed by a human at the N07 gate; this executor approves nothing.
 */
@Component
public class DesignStageExecutor implements StageExecutor {

    private final RequirementStore requirements;
    private final TaskStore tasks;
    private final ArtifactStore artifacts;
    private final ObjectMapper json = new ObjectMapper();

    public DesignStageExecutor(RequirementStore requirements, TaskStore tasks, ArtifactStore artifacts) {
        this.requirements = requirements;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        String runId = context.runId();
        Map<String, String> taskTitles = tasks.titles(runId);
        if (taskTitles.isEmpty()) {
            return StageResult.failed("permanent: no decomposed tasks to design for");
        }
        Map<String, Object> design = new LinkedHashMap<>();
        design.put("requirement", requirements.content(runId));
        JsonNode report = requirements.qualityReport(runId).map(this::read).orElse(null);
        if (report != null) {
            design.put("kind", report.path("kind").asText());
            design.put("quality_rationale", report.path("rationale").asText());
        }
        design.put("tasks", taskTitles);
        Optional<ArtifactStore.Artifact> impact = artifacts.latest(runId, "IMPACT_ANALYSIS").map(a -> artifacts.read(runId, a.type(), "N06"));
        if (impact.isPresent()) {
            JsonNode i = read(impact.get().content());
            Map<String, Object> impacts = new LinkedHashMap<>();
            impacts.put("impact_analysis", impact.get().id() + "@" + impact.get().version() + "#" + impact.get().contentHash());
            impacts.put("components", i.path("components"));
            impacts.put("interfaces", i.path("interfaces"));
            impacts.put("data_flows", i.path("data_flows"));
            design.put("api_and_schema_impacts", impacts);
        } else {
            design.put("api_and_schema_impacts", "none identified: greenfield change, no existing interface or schema is modified"
                    + " (additive /api/v1 capability only)");
        }
        List<String> criteria = new ArrayList<>();
        taskTitles.forEach((key, title) -> criteria.add(key + ": " + title + " - implemented and covered by at least one passing test"));
        criteria.add("every mandatory policy check passes or carries an approved exception (FR-033)");
        design.put("acceptance_criteria", criteria);
        artifacts.record(runId, "DESIGN", json.writeValueAsString(design), "DETERMINISTIC", "N06");
        return StageResult.succeeded();
    }

    private JsonNode read(String content) {
        try {
            return json.readTree(content);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
