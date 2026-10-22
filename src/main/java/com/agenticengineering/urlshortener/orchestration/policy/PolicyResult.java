package com.agenticengineering.urlshortener.orchestration.policy;

import java.util.List;

/** The outcome of evaluating a policy set for one run: the version and one evaluation per check, in inventory order. */
public record PolicyResult(String policyVersion, List<Evaluation> evaluations, List<String> mandatoryFailures) {

    public record Evaluation(String checkId, PolicyOutcome outcome, String evidenceRef, Long exceptionId, String reason) {
    }

    public Evaluation evaluation(String checkId) {
        return evaluations.stream().filter(e -> e.checkId().equals(checkId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no evaluation for " + checkId));
    }

    public PolicyOutcome outcome(String checkId) {
        return evaluation(checkId).outcome();
    }
}
