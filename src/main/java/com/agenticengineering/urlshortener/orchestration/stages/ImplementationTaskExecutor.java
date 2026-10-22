package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskExecutor;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * N08 implementation task (plan section 3): code is written by the engineer (AI-assisted, human-reviewed) and committed
 * to the workspace with the marker "[run {id} task {key}]". The task succeeds when such a commit exists, recording a
 * CODE artifact with the commit and the files it changed (provenance AI_ASSISTED_HUMAN_REVIEWED); otherwise it fails
 * permanently, the run safe-stops, and an ENGINEER resumes it after committing. No code is ever generated or assumed.
 */
public final class ImplementationTaskExecutor implements TaskExecutor {

    private final Path workspace;
    private final ArtifactStore artifacts;
    private final ObjectMapper json = new ObjectMapper();

    private ImplementationTaskExecutor(Path workspace, ArtifactStore artifacts) {
        this.workspace = workspace;
        this.artifacts = artifacts;
    }

    @Override
    public StageResult execute(String taskKey) {
        throw new UnsupportedOperationException("the implementation executor needs the run id");
    }

    @Override
    public StageResult execute(String runId, String taskKey) throws Exception {
        artifacts.readIfPresent(runId, "DESIGN", "N08"); // the task implements this design version (replan trigger, T089)
        String marker = "[run " + runId + " task " + taskKey + "]";
        String log = Workspace.git(workspace, "log", "--format=%H", "--fixed-strings", "--grep=" + marker);
        if (log == null) {
            return StageResult.failed("permanent: workspace " + workspace + " is not a readable git repository");
        }
        if (log.isBlank()) {
            return StageResult.failed("permanent: no implementation commit for task " + taskKey + "; commit the change with "
                    + marker + " in the message and resume the run");
        }
        String commit = log.lines().findFirst().orElseThrow();
        String files = Workspace.git(workspace, "show", "--name-only", "--format=", commit);
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("task", taskKey);
        code.put("commit", commit);
        code.put("files", files == null || files.isBlank() ? List.of() : Arrays.asList(files.split("\n")));
        artifacts.record(runId, "CODE", json.writeValueAsString(code), "AI_ASSISTED_HUMAN_REVIEWED", "N08");
        return StageResult.succeeded();
    }

    /** Builds implementation executors for a workspace. */
    @Component
    public static class Factory {

        private final ArtifactStore artifacts;
        private final String workspace;

        public Factory(ArtifactStore artifacts, @Value("${app.workspace-root:.}") String workspace) {
            this.artifacts = artifacts;
            this.workspace = workspace;
        }

        public ImplementationTaskExecutor forWorkspace(Path root) {
            return new ImplementationTaskExecutor(root, artifacts);
        }

        public ImplementationTaskExecutor production() {
            return forWorkspace(Path.of(workspace).toAbsolutePath().normalize());
        }
    }
}
