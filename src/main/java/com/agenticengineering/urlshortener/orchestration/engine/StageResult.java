package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.Set;

/** What a stage executor reports: success (with the conditional branches it selects) or failure with a reason. */
public record StageResult(Outcome outcome, Set<String> selectedBranches, String reason) {

    public enum Outcome { SUCCEEDED, FAILED }

    public static StageResult succeeded() {
        return new StageResult(Outcome.SUCCEEDED, Set.of(), null);
    }

    public static StageResult succeeded(Set<String> selectedBranches) {
        return new StageResult(Outcome.SUCCEEDED, Set.copyOf(selectedBranches), null);
    }

    public static StageResult failed(String reason) {
        return new StageResult(Outcome.FAILED, Set.of(), reason);
    }
}
