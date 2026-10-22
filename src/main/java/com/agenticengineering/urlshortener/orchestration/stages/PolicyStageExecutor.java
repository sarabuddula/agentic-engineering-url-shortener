package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.PolicyStore;
import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyEvaluator;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyEvidenceProvider;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyResult;
import com.agenticengineering.urlshortener.orchestration.policy.PolicySet;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * N12 policy evaluation (T062; FR-032, FR-033, FR-037; Constitution VI). Records every check, and on a mandatory
 * failure persists NOT_READY with the blocking reasons, audits RELEASE_AUTHORIZATION_BLOCKED (negative-path evidence for
 * CMP-02) and does not select N13: release authorization is never offered. N14 still runs, reporting only.
 */
public final class PolicyStageExecutor implements StageExecutor {

    private final PolicyEvidenceProvider evidence;
    private final PolicyStore policies;
    private final ReleaseDecisionStore releases;
    private final WorkflowStore store;
    private final AuditChain audit;
    private final TimeSource clock;
    private final PolicyEvaluator evaluator = new PolicyEvaluator(PolicySet.v20261018());

    private PolicyStageExecutor(PolicyEvidenceProvider evidence, PolicyStore policies, ReleaseDecisionStore releases,
            WorkflowStore store, AuditChain audit, TimeSource clock) {
        this.evidence = evidence;
        this.policies = policies;
        this.releases = releases;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public StageResult execute(RunContext context) {
        PolicyResult result = evaluator.evaluate(evidence.gather(context.runId()));
        policies.record(context.runId(), result);
        if (result.mandatoryFailures().isEmpty()) {
            return StageResult.succeeded(Set.of("N13"));
        }
        List<String> reasons = result.mandatoryFailures().stream()
                .map(id -> id + " FAIL: " + result.evaluation(id).reason()
                        + (result.evaluation(id).evidenceRef() != null ? " (" + result.evaluation(id).evidenceRef() + ")" : ""))
                .toList();
        releases.recordRecommendation(context.runId(), "NOT_READY", reasons, store.requesterKeyId(context.runId()));
        audit.append(context.runId(), new AuditEntry(AuditEntry.ActorType.SYSTEM, "RELEASE_AUTHORIZATION_BLOCKED", clock.now(), "N13",
                "BLOCKED", "mandatory policy failure(s) " + result.mandatoryFailures() + "; release gate not offered"));
        return StageResult.succeeded(Set.of());
    }

    /** Builds N12 executors with a given evidence source. */
    @Component
    public static class Factory {

        private final PolicyStore policies;
        private final ReleaseDecisionStore releases;
        private final WorkflowStore store;
        private final AuditChain audit;
        private final TimeSource clock;

        public Factory(PolicyStore policies, ReleaseDecisionStore releases, WorkflowStore store, AuditChain audit, TimeSource clock) {
            this.policies = policies;
            this.releases = releases;
            this.store = store;
            this.audit = audit;
            this.clock = clock;
        }

        public PolicyStageExecutor withEvidence(PolicyEvidenceProvider evidence) {
            return new PolicyStageExecutor(evidence, policies, releases, store, audit, clock);
        }
    }
}
