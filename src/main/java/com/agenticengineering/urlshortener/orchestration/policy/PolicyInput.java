package com.agenticengineering.urlshortener.orchestration.policy;

import java.time.Instant;
import java.util.Map;

/**
 * Everything the evaluator sees for one run: evidence per automated check, recorded human attestations, and the
 * policy-exception records that exist for failing checks (validity is judged at release readiness, T062/T063).
 */
public record PolicyInput(Map<String, Evidence> evidence, Map<String, Attestation> attestations,
        Map<String, ExceptionRef> exceptions) {

    public PolicyInput {
        evidence = Map.copyOf(evidence);
        attestations = Map.copyOf(attestations);
        exceptions = Map.copyOf(exceptions);
    }

    /** A recorded human attestation (key ID of the attesting human and where the record is). */
    public record Attestation(String attestedByKeyId, String ref) {
    }

    /** A POLICY_EXCEPTION row for a check. */
    public record ExceptionRef(long exceptionId, Instant expiresAt) {
    }
}
