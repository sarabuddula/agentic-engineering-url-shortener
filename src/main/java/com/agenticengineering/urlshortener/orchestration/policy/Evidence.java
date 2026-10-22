package com.agenticengineering.urlshortener.orchestration.policy;

/**
 * Evidence for one automated check, gathered by N12 from executed artifacts (test outcomes, scans, chain verification).
 * DEGRADED means the automated source could not run (for example DEP-02 offline) and an attestation is required.
 */
public record Evidence(Status status, String ref, String reason) {

    public enum Status { OK, NOT_OK, NOT_APPLICABLE, DEGRADED }

    public static Evidence ok(String ref) {
        return new Evidence(Status.OK, ref, null);
    }

    public static Evidence notOk(String ref) {
        return new Evidence(Status.NOT_OK, ref, null);
    }

    public static Evidence notApplicable(String reason) {
        return new Evidence(Status.NOT_APPLICABLE, null, reason);
    }

    public static Evidence degraded(String ref) {
        return new Evidence(Status.DEGRADED, ref, null);
    }
}
