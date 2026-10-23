package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import org.springframework.stereotype.Component;

/**
 * Enforces the per-node whitelist at the guarded operations (artifact read and write, approval request, test-suite run,
 * policy-evaluation write). Inside a stage executor an operation outside the node's list is audited OPERATION_BLOCKED
 * (actor AGENT, result BLOCKED) and refused; outside any executor (people through the API) role checks govern instead.
 */
@Component
public class OperationGuard {

    private final AuditChain audit;
    private final TimeSource clock;

    public OperationGuard(AuditChain audit, TimeSource clock) {
        this.audit = audit;
        this.clock = clock;
    }

    public void require(OperationWhitelist.Op op) {
        OperationWhitelist.current().ifPresent(context -> {
            if (!OperationWhitelist.allowed(context.node()).contains(op)) {
                audit.append(context.runId(), new AuditEntry(AuditEntry.ActorType.AGENT, "OPERATION_BLOCKED", clock.now(), context.node(),
                        "BLOCKED", op + " is not in the " + context.node() + " whitelist " + OperationWhitelist.allowed(context.node())
                                + " (plan section 3)"));
                throw new OperationNotPermittedException(op + " not permitted at " + context.node());
            }
        });
    }
}
