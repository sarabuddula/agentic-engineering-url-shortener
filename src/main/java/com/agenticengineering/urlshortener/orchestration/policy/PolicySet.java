package com.agenticengineering.urlshortener.orchestration.policy;

import java.util.List;

/** A versioned policy set (FR-032). POL-2026-10-18-v1 is the data-model.md inventory; every check is mandatory. */
public record PolicySet(String version, List<Check> checks) {

    public enum Mode { AUTOMATED, ATTESTATION }

    public record Check(String id, String domain, String verifies, Mode mode, boolean mandatory) {
    }

    public Check check(String id) {
        return checks.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no check " + id + " in " + version));
    }

    public static PolicySet v20261018() {
        return new PolicySet("POL-2026-10-18-v1", List.of(
                auto("SEC-01", "Security", "input validation and HTTP/HTTPS allowlist enforced"),
                auto("SEC-02", "Security", "internal/local address rejection rule set enforced (D-003)"),
                auto("SEC-03", "Security", "no secrets or raw keys in logs or committed files"),
                auto("CMP-01", "Compliance", "policy version recorded; exactly one outcome per applicable check"),
                auto("CMP-02", "Compliance", "failed mandatory checks verifiably block authorization"),
                auto("PRV-01", "Privacy", "analytics fields limited to the approved set"),
                attest("PRV-02", "Privacy", "no PII in logs or analytics beyond the approved minimum"),
                auto("AUD-01", "Audit retention", "no update/delete API on audit or workflow records"),
                auto("AUD-02", "Audit retention", "hash-chain verification passes (N12, re-verified at N14)"),
                auto("DEP-01", "Approved dependencies", "every runtime dependency pinned and declared in the inventory"),
                auto("DEP-02", "Approved dependencies", "dependency vulnerability scan clean (attestation when degraded)"),
                auto("LIC-01", "Licensing", "all dependency licenses approved"),
                attest("LIC-02", "Licensing", "third-party notices present in the distribution docs"),
                auto("CHG-01", "Change control", "changes to approved artifacts carry approval records"),
                auto("CHG-02", "Change control", "downstream revalidation recorded on replan triggers"),
                auto("CHG-03", "Change control", "bidirectional traceability complete")));
    }

    private static Check auto(String id, String domain, String verifies) {
        return new Check(id, domain, verifies, Mode.AUTOMATED, true);
    }

    private static Check attest(String id, String domain, String verifies) {
        return new Check(id, domain, verifies, Mode.ATTESTATION, true);
    }
}
