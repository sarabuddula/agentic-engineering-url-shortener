package com.agenticengineering.urlshortener.orchestration.persistence;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyOutcome;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyResult;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a policy evaluation (T057, FR-032): one POLICY_EVALUATION row per check and a POLICY_EVALUATED audit event
 * naming the policy version, the outcome counts and the mandatory failures, in one transaction.
 */
@Component
public class PolicyStore {

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;

    public PolicyStore(JdbcTemplate jdbc, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    public void record(String runId, PolicyResult result) {
        tx.executeWithoutResult(status -> {
            Map<PolicyOutcome, Integer> counts = new EnumMap<>(PolicyOutcome.class);
            for (PolicyOutcome outcome : PolicyOutcome.values()) {
                counts.put(outcome, 0);
            }
            for (PolicyResult.Evaluation e : result.evaluations()) {
                jdbc.update("INSERT INTO POLICY_EVALUATION (RUN_ID, CHECK_ID, OUTCOME, EVIDENCE_REF, EXCEPTION_ID) VALUES (?, ?, ?, ?, ?)",
                        runId, e.checkId(), e.outcome().name(), e.evidenceRef(), e.exceptionId());
                counts.merge(e.outcome(), 1, Integer::sum);
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.AGENT, "POLICY_EVALUATED", clock.now(), result.policyVersion(),
                    counts.get(PolicyOutcome.PASS) + " PASS, " + counts.get(PolicyOutcome.FAIL) + " FAIL, "
                            + counts.get(PolicyOutcome.EXCEPTION_REQUESTED) + " EXCEPTION_REQUESTED, "
                            + counts.get(PolicyOutcome.NOT_APPLICABLE) + " NOT_APPLICABLE",
                    "mandatory failures: " + result.mandatoryFailures()));
        });
    }
}
