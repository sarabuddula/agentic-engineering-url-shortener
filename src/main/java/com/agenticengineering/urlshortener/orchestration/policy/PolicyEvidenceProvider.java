package com.agenticengineering.urlshortener.orchestration.policy;

/** Gathers the evidence, attestations and exception records N12 evaluates for a run. */
@FunctionalInterface
public interface PolicyEvidenceProvider {

    PolicyInput gather(String runId);
}
