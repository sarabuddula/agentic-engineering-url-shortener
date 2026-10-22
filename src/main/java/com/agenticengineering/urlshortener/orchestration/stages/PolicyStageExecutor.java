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
import com.agenticengineering.urlshortener.orchestration.policy.PolicyExceptionService;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyOutcome;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyResult;
import com.agenticengineering.urlshortener.orchestration.policy.PolicySet;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * N12 policy evaluation (T062; FR-032, FR-033, FR-037; Constitution VI). Records every check, and on a mandatory
 * failure, or a failing check whose exception is unapproved or expired (T063), persists NOT_READY with the blocking reasons, audits RELEASE_AUTHORIZATION_BLOCKED (negative-path evidence for
 * CMP-02) and does not select N13: release authorization is never offered. N14 still runs, reporting only.
 */
public final class PolicyStageExecutor implements StageExecutor {

    private final PolicyEvidenceProvider evidence;
    private final PolicyStore policies;
    private final ReleaseDecisionStore releases;
    private final WorkflowStore store;
    private final AuditChain audit;
    private final TimeSource clock;
    private final PolicyExceptionService exceptions;
    private final PolicyEvaluator evaluator = new PolicyEvaluator(PolicySet.v20261018());

    private PolicyStageExecutor(PolicyEvidenceProvider evidence, PolicyStore policies, ReleaseDecisionStore releases,
            WorkflowStore store, AuditChain audit, TimeSource clock, PolicyExceptionService exceptions) {
        this.evidence = evidence;
        this.policies = policies;
        this.releases = releases;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.exceptions = exceptions;
    }

    @Override
    public StageResult execute(RunContext context) {
        String runId = context.runId();
        Instant now = clock.now();
        // exception records always come from persisted, human-approved rows, never from the evidence source
        Map<String, PolicyExceptionService.PolicyException> recorded = exceptions.latestPerCheck(runId);
        PolicyInput gathered = evidence.gather(runId);
        Map<String, PolicyInput.ExceptionRef> refs = new HashMap<>();
        recorded.forEach((check, e) -> refs.put(check, new PolicyInput.ExceptionRef(e.id(), e.expiresAt())));
        PolicyResult result = evaluator.evaluate(new PolicyInput(gathered.evidence(), gathered.attestations(), refs));
        policies.record(runId, result);

        List<String> blockers = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String id : result.mandatoryFailures()) {
            PolicyResult.Evaluation e = result.evaluation(id);
            blockers.add(id);
            reasons.add(id + " FAIL: " + e.reason() + (e.evidenceRef() != null ? " (" + e.evidenceRef() + ")" : ""));
        }
        for (PolicyResult.Evaluation e : result.evaluations()) {
            if (e.outcome() == PolicyOutcome.EXCEPTION_REQUESTED && PolicySet.v20261018().check(e.checkId()).mandatory()) {
                PolicyExceptionService.PolicyException exception = recorded.get(e.checkId());
                if (!exception.validAt(now)) {
                    blockers.add(e.checkId());
                    reasons.add(e.checkId() + " EXCEPTION_REQUESTED: exception " + exception.id()
                            + (exception.approved() ? " expired at " + exception.expiresAt() : " not approved"));
                }
            }
        }
        if (blockers.isEmpty()) {
            return StageResult.succeeded(Set.of("N13"));
        }
        releases.recordRecommendation(runId, "NOT_READY", reasons, store.requesterKeyId(runId));
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "RELEASE_AUTHORIZATION_BLOCKED", now, "N13",
                "BLOCKED", "blocking policy check(s) " + blockers + "; release gate not offered"));
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
        private final PolicyExceptionService exceptions;

        public Factory(PolicyStore policies, ReleaseDecisionStore releases, WorkflowStore store, AuditChain audit, TimeSource clock,
                PolicyExceptionService exceptions) {
            this.policies = policies;
            this.releases = releases;
            this.store = store;
            this.audit = audit;
            this.clock = clock;
            this.exceptions = exceptions;
        }

        public PolicyStageExecutor withEvidence(PolicyEvidenceProvider evidence) {
            return new PolicyStageExecutor(evidence, policies, releases, store, audit, clock, exceptions);
        }
    }
}
