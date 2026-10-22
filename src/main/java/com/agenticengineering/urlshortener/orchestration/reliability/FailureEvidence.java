package com.agenticengineering.urlshortener.orchestration.reliability;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Failure evidence in its own transaction (T123; FR-027, FR-030, NFR-005): the FAILURE_EVENT and the OPERATION_FAILED
 * audit entry commit independently (REQUIRES_NEW), so rolling back the failed operation never erases them.
 * Constraint: the caller's transaction must not hold the run's audit lock (an audit append in the same transaction),
 * because the new transaction appends to the same chain; record the failure before or instead of auditing in it.
 */
@Component
public class FailureEvidence {

    private final FailureRecorder recorder;
    private final WorkflowStore store;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate independent;

    public FailureEvidence(FailureRecorder recorder, WorkflowStore store, AuditChain audit, TimeSource clock,
            PlatformTransactionManager transactions) {
        this.recorder = recorder;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records detection of a failed operation at the node; returns the FAILURE_EVENT id. */
    public long operationFailed(String runId, String node, String reason) {
        return independent.execute(status -> {
            long id = recorder.detected(runId, store.nodeId(runId, node), clock.now());
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "OPERATION_FAILED", clock.now(), node, "FAILED",
                    reason.length() > 900 ? reason.substring(0, 900) + "..." : reason));
            return id;
        });
    }

    /** Runs the recording in its own transaction (compensation and rejection records). */
    public void independently(Runnable recording) {
        independent.executeWithoutResult(status -> recording.run());
    }
}
