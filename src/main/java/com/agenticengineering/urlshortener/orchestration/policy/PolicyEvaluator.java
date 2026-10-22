package com.agenticengineering.urlshortener.orchestration.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a versioned policy set (T057; FR-032, FR-033, FR-039; Constitution VI). Rules: missing evidence fails
 * closed; attestation checks pass only with a recorded human attestation; degraded automated evidence needs an
 * attestation too; a failing check with an exception record becomes EXCEPTION_REQUESTED (its validity is judged at
 * release readiness); CMP-01 is computed by the evaluator itself. Framework-free and deterministic.
 */
public class PolicyEvaluator {

    private static final String SELF_CHECK = "CMP-01";

    private final PolicySet policySet;

    public PolicyEvaluator(PolicySet policySet) {
        this.policySet = Objects.requireNonNull(policySet);
    }

    public PolicyResult evaluate(PolicyInput input) {
        List<PolicyResult.Evaluation> evaluations = new ArrayList<>();
        for (PolicySet.Check check : policySet.checks()) {
            if (check.id().equals(SELF_CHECK)) {
                evaluations.add(null); // filled in once every other check has its outcome
            } else {
                evaluations.add(withException(evaluate(check, input), input));
            }
        }
        int self = indexOf(SELF_CHECK);
        boolean versioned = policySet.version() != null && !policySet.version().isBlank();
        evaluations.set(self, new PolicyResult.Evaluation(SELF_CHECK, versioned ? PolicyOutcome.PASS : PolicyOutcome.FAIL,
                "policyVersion=" + policySet.version(), null,
                versioned ? "version recorded; exactly one outcome for each of " + policySet.checks().size() + " checks"
                        : "policy version missing"));
        List<String> mandatoryFailures = new ArrayList<>();
        for (PolicyResult.Evaluation e : evaluations) {
            if (e.outcome() == PolicyOutcome.FAIL && policySet.check(e.checkId()).mandatory()) {
                mandatoryFailures.add(e.checkId());
            }
        }
        return new PolicyResult(policySet.version(), List.copyOf(evaluations), List.copyOf(mandatoryFailures));
    }

    private static PolicyResult.Evaluation evaluate(PolicySet.Check check, PolicyInput input) {
        PolicyInput.Attestation attestation = input.attestations().get(check.id());
        if (check.mode() == PolicySet.Mode.ATTESTATION) {
            return attestation != null
                    ? new PolicyResult.Evaluation(check.id(), PolicyOutcome.PASS, attestation.ref(), null,
                            "attested by " + attestation.attestedByKeyId())
                    : new PolicyResult.Evaluation(check.id(), PolicyOutcome.FAIL, null, null, "no recorded attestation");
        }
        Evidence evidence = input.evidence().get(check.id());
        if (evidence == null) {
            return new PolicyResult.Evaluation(check.id(), PolicyOutcome.FAIL, null, null, "no evidence (fails closed)");
        }
        return switch (evidence.status()) {
            case OK -> new PolicyResult.Evaluation(check.id(), PolicyOutcome.PASS, evidence.ref(), null, "evidence verified");
            case NOT_OK -> new PolicyResult.Evaluation(check.id(), PolicyOutcome.FAIL, evidence.ref(), null, "evidence shows a violation");
            case NOT_APPLICABLE -> new PolicyResult.Evaluation(check.id(), PolicyOutcome.NOT_APPLICABLE, null, null, evidence.reason());
            case DEGRADED -> attestation != null
                    ? new PolicyResult.Evaluation(check.id(), PolicyOutcome.PASS,
                            evidence.ref() + "; attestation: " + attestation.ref(), null,
                            "automated source degraded; attested by " + attestation.attestedByKeyId())
                    : new PolicyResult.Evaluation(check.id(), PolicyOutcome.FAIL, evidence.ref(), null,
                            "automated source degraded and no recorded attestation");
        };
    }

    private static PolicyResult.Evaluation withException(PolicyResult.Evaluation evaluation, PolicyInput input) {
        PolicyInput.ExceptionRef exception = input.exceptions().get(evaluation.checkId());
        if (evaluation.outcome() != PolicyOutcome.FAIL || exception == null) {
            return evaluation;
        }
        return new PolicyResult.Evaluation(evaluation.checkId(), PolicyOutcome.EXCEPTION_REQUESTED, evaluation.evidenceRef(),
                exception.exceptionId(), "failing check covered by exception " + exception.exceptionId() + " (validity judged at release)");
    }

    private int indexOf(String checkId) {
        for (int i = 0; i < policySet.checks().size(); i++) {
            if (policySet.checks().get(i).id().equals(checkId)) {
                return i;
            }
        }
        throw new IllegalStateException(checkId + " missing from " + policySet.version());
    }
}
