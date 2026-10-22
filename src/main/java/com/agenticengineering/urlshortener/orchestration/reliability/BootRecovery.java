package com.agenticengineering.urlshortener.orchestration.reliability;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Boot recovery (T122; FR-028, PVT-006; human decision 2026-10-19). At startup, every run found RUNNING or
 * AWAITING_APPROVAL is safe-stopped with cause BOOT_RECOVERY (audited): nodes that were executing are safe-stopped,
 * pending approvals stay pending (never auto-approved), and resumption needs an authorized POST /resume. PENDING
 * operations are reconciled by effect verification: an applied effect is marked verified and completed (never
 * re-executed); a missing one stays pending; an unverifiable one is reported.
 */
@Component
public class BootRecovery {

    private final JdbcTemplate jdbc;
    private final WorkflowStore store;
    private final EffectVerifier verifier;
    private final OperationLog operations;
    private final AuditChain audit;
    private final TimeSource clock;

    public BootRecovery(JdbcTemplate jdbc, WorkflowStore store, EffectVerifier verifier, OperationLog operations, AuditChain audit,
            TimeSource clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.verifier = verifier;
        this.operations = operations;
        this.audit = audit;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (String runId : jdbc.queryForList("SELECT ID FROM WORKFLOW_RUN WHERE STATUS IN ('RUNNING', 'AWAITING_APPROVAL') ORDER BY ID",
                String.class)) {
            reconcile(runId);
            store.nodeStatuses(runId).forEach((node, status) -> {
                if (status == NodeStatus.RUNNING) {
                    store.transitionNode(runId, node, NodeStatus.RUNNING, NodeStatus.FAILED, "interrupted by a JVM stop");
                    store.transitionNode(runId, node, NodeStatus.FAILED, NodeStatus.SAFE_STOPPED, "BOOT_RECOVERY");
                }
            });
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "BOOT_RECOVERY", clock.now(), runId, "SAFE_STOPPED",
                    "run found " + store.runStatus(runId) + " at startup; safe-stopped, pending approvals kept, resume requires an authorized POST /resume"));
            store.safeStopRun(runId, store.runStatus(runId), "BOOT_RECOVERY", "boot recovery (FR-028)");
        }
    }

    private void reconcile(String runId) {
        List<Map<String, Object>> pending = jdbc.queryForList("SELECT OPERATION_ID, TYPE, NODE_ID FROM OPERATION_RECORD WHERE RUN_ID = ?"
                + " AND STATUS = 'PENDING' ORDER BY CREATED_AT", runId);
        for (Map<String, Object> op : pending) {
            String id = (String) op.get("OPERATION_ID");
            EffectVerifier.Verdict verdict = verifier.verify(runId, (String) op.get("TYPE"), id, ((Number) op.get("NODE_ID")).longValue());
            if (verdict == EffectVerifier.Verdict.APPLIED) {
                operations.complete(id, true);
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "OPERATION_RECONCILED", clock.now(), id, verdict.name(),
                    "operation " + id + " (" + op.get("TYPE") + "): " + switch (verdict) {
                        case APPLIED -> "effect found; marked completed and verified, not re-executed";
                        case NOT_APPLIED -> "no effect; left pending, safe to execute on resume";
                        case UNVERIFIABLE -> "no verification rule; the run stays safe-stopped until a human resolves it";
                    }));
        }
    }
}
