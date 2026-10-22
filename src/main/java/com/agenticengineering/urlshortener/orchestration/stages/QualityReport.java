package com.agenticengineering.urlshortener.orchestration.stages;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * N02 requirement-quality report (FR-034, FR-035): the four checks, the material findings, the branch selection for
 * the stage graph (N03 clarification, N04 impact analysis) and the rationale recorded with the run.
 */
public record QualityReport(String normalizedText, Kind kind, Map<String, Outcome> checks, List<Finding> findings,
        boolean materialAmbiguity, Set<String> selectedBranches, String rationale) {

    public enum Outcome { PASS, FAIL }

    /** NEW_CAPABILITY (greenfield), CHANGE_REQUEST (brownfield: changes existing behaviour), UNCLASSIFIED. */
    public enum Kind { NEW_CAPABILITY, CHANGE_REQUEST, UNCLASSIFIED }

    /** A material finding (spec Appendix A): the term, why it matters, and the interpretations it admits. */
    public record Finding(String id, String term, String reason, List<String> interpretations) {
    }
}
