package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * N02 requirement-quality check (FR-034, FR-035): stores the quality report with the requirement, audits the outcome
 * and rationale, and selects the N03 clarification and N04 impact-analysis branches as the report decides.
 */
@Component
public class QualityStageExecutor implements StageExecutor {

    private final RequirementStore requirements;
    private final AuditChain audit;
    private final TimeSource clock;
    private final RequirementQualityChecker checker = new RequirementQualityChecker();
    private final ObjectMapper json = new ObjectMapper();

    public QualityStageExecutor(RequirementStore requirements, AuditChain audit, TimeSource clock) {
        this.requirements = requirements;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        QualityReport report = checker.check(requirements.content(context.runId()));
        requirements.saveQualityReport(context.runId(), json.writeValueAsString(report));
        audit.append(context.runId(), new AuditEntry(AuditEntry.ActorType.AGENT, "QUALITY_CHECKED", clock.now(), "N02",
                report.materialAmbiguity() ? "AMBIGUOUS" : "WELL_DEFINED", report.rationale()));
        return StageResult.succeeded(report.selectedBranches());
    }
}
