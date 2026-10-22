package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.policy.PersistedEvidenceProvider;
import com.agenticengineering.urlshortener.orchestration.stages.PolicyStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.DecompositionStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.DesignStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.DocumentationStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.EvidenceStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.ImpactAnalysisStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.ImplementationTaskExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.TestExecutionStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.QualityStageExecutor;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The production stage executors. Stages whose executor is not built yet fail with a message naming the task that
 * builds it, so a run safe-stops visibly instead of producing fabricated evidence (Constitution X).
 */
@Component
public class StageExecutorRegistry {

    private static final Map<String, String> NOT_YET_BUILT = Map.of("N14", "T091 engineering summary");

    private final RequirementStore requirements;
    private final QualityStageExecutor quality;
    private final DecompositionStageExecutor decomposition;
    private final GateExecutor.Factory gates;
    private final PolicyStageExecutor policy;
    private final ImpactAnalysisStageExecutor impact;
    private final TestExecutionStageExecutor tests;
    private final DesignStageExecutor design;
    private final DocumentationStageExecutor documentation;
    private final EvidenceStageExecutor evidenceAssembly;
    private final ImplementationTaskExecutor implementation;

    public StageExecutorRegistry(RequirementStore requirements, QualityStageExecutor quality,
            DecompositionStageExecutor decomposition, GateExecutor.Factory gates, PolicyStageExecutor.Factory policies,
            PersistedEvidenceProvider evidence, ImpactAnalysisStageExecutor impact, TestExecutionStageExecutor.Factory tests,
            DesignStageExecutor design, DocumentationStageExecutor.Factory documentation, EvidenceStageExecutor evidenceAssembly,
            ImplementationTaskExecutor.Factory implementation) {
        this.requirements = requirements;
        this.quality = quality;
        this.decomposition = decomposition;
        this.gates = gates;
        this.policy = policies.withEvidence(evidence);
        this.impact = impact;
        this.tests = tests.production();
        this.design = design;
        this.documentation = documentation.production();
        this.evidenceAssembly = evidenceAssembly;
        this.implementation = implementation.production();
    }

    public Map<String, StageExecutor> executors() {
        Map<String, StageExecutor> executors = new HashMap<>();
        executors.put("N01", ctx -> requirements.exists(ctx.runId()) ? StageResult.succeeded()
                : StageResult.failed("permanent: no requirement artifact"));
        executors.put("N02", quality);
        executors.put("N03", gates.gate(ApprovalKind.CLARIFICATION));
        executors.put("N04", impact);
        executors.put("N05", decomposition);
        executors.put("N06", design);
        executors.put("N09", tests); // T128: real test execution
        executors.put("N10", documentation);
        executors.put("N11", evidenceAssembly);
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.put("N12", policy); // production evidence is partial: unverified checks fail closed (NOT READY)
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        NOT_YET_BUILT.forEach((node, task) -> executors.put(node,
                ctx -> StageResult.failed("permanent: executor for " + node + " not built yet (" + task + ")")));
        return executors;
    }

    public TaskExecutor taskExecutor() {
        return implementation; // N08: the task's marked commit in the workspace is the implementation evidence
    }
}
