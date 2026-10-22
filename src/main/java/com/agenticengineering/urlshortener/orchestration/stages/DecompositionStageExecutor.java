package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import org.springframework.stereotype.Component;

/**
 * N05 decomposition (FR-036): template tasks with explicit dependency rows; refuses while a material ambiguity is open
 * (FR-035). Idempotent: an existing decomposition is kept on resumption.
 */
@Component
public class DecompositionStageExecutor implements StageExecutor {

    private final RequirementStore requirements;
    private final TaskStore tasks;
    private final RequirementQualityChecker checker = new RequirementQualityChecker();
    private final Decomposer decomposer = new Decomposer();

    public DecompositionStageExecutor(RequirementStore requirements, TaskStore tasks) {
        this.requirements = requirements;
        this.tasks = tasks;
    }

    @Override
    public StageResult execute(RunContext context) {
        if (!tasks.statuses(context.runId()).isEmpty()) {
            return StageResult.succeeded();
        }
        try {
            tasks.saveDecomposition(context.runId(), decomposer.decompose(checker.check(requirements.content(context.runId())),
                    requirements.requirementRef(context.runId())));
            return StageResult.succeeded();
        } catch (OpenAmbiguityException e) {
            return StageResult.failed("permanent: " + e.getMessage());
        }
    }
}
