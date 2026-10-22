package com.agenticengineering.urlshortener.orchestration.reliability;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rollback for the one reversible class of operation (T076; FR-027; data-model "Rollback vs compensation"): uncommitted
 * rows of a deterministic transform (N01, N02, N05, N06). The work runs in one transaction; if it fails, the transaction
 * rolls back, the remaining rows are counted to verify the rollback, and the failure and ROLLBACK_APPLIED are recorded in
 * their own transactions (T123). Anything irreversible goes through CompensationService instead.
 */
@Component
public class ReversibleOperations {

    public record Outcome(boolean rolledBack, int remainingRows, String cause) {
    }

    private final FailureEvidence failures;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate independent;

    public ReversibleOperations(FailureEvidence failures, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions) {
        this.failures = failures;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Runs the work reversibly. {@code remainingRows} counts the rows the work writes; after a rollback it must be zero.
     */
    public Outcome run(String runId, String node, String description, Supplier<Integer> remainingRows, Runnable work) {
        try {
            tx.executeWithoutResult(status -> work.run());
            return new Outcome(false, remainingRows.get(), null);
        } catch (RuntimeException e) {
            String cause = e.getClass().getSimpleName() + ": " + e.getMessage();
            int remaining = remainingRows.get();
            failures.operationFailed(runId, node, description + " failed: " + cause);
            String reason = description + " rolled back after: " + cause + "; verified: " + remaining + " rows remain";
            independent.executeWithoutResult(s -> audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "ROLLBACK_APPLIED",
                    clock.now(), node, remaining == 0 ? "ROLLED_BACK" : "ROLLBACK_INCOMPLETE", bounded(reason))));
            return new Outcome(true, remaining, cause);
        }
    }

    private static String bounded(String value) {
        return value.length() > 900 ? value.substring(0, 900) + "..." : value;
    }
}
