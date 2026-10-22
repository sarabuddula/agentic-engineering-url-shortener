package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import java.util.List;

/**
 * N05 decomposition from approved templates (T056, FR-036): tasks with explicit dependencies, tests after the
 * implementation they verify. Refused while a material ambiguity is open (FR-035): no implementation work product is
 * derived from an ambiguous requirement.
 */
public class Decomposer {

    public List<TaskSpec> decompose(QualityReport report, String requirementRef) {
        if (report.materialAmbiguity()) {
            throw new OpenAmbiguityException(report.findings().size());
        }
        return switch (report.kind()) {
            case CHANGE_REQUEST -> List.of(
                    new TaskSpec("t1", "Apply the approved change to configuration and code", requirementRef, List.of()),
                    new TaskSpec("t2", "Update tests that assert the superseded behaviour", requirementRef, List.of("t1")),
                    new TaskSpec("t3", "Run the regression suite over the impacted components", requirementRef, List.of("t2")),
                    new TaskSpec("t4", "Update documentation for the changed behaviour", requirementRef, List.of("t1")));
            case NEW_CAPABILITY, UNCLASSIFIED -> List.of(
                    new TaskSpec("t1", "API contract: additive /api/v1 change in openapi.yaml", requirementRef, List.of()),
                    new TaskSpec("t2", "Domain logic for the capability", requirementRef, List.of("t1")),
                    new TaskSpec("t3", "Persistence query for the capability", requirementRef, List.of("t1")),
                    new TaskSpec("t4", "Tests: contract, unit and integration for the capability", requirementRef, List.of("t2", "t3")),
                    new TaskSpec("t5", "Documentation: API notes and quickstart", requirementRef, List.of("t1")));
        };
    }
}
