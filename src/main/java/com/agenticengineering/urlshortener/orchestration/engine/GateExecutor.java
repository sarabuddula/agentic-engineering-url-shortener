package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import org.springframework.stereotype.Component;

/**
 * A human gate node (N03 G1, N07 G2, N13 G7; plan section 3): requests the approval on behalf of the run's creator
 * and waits. The node can only succeed once a human with the gate's role and a different key identity approves
 * (FR-024, FR-046); the engine settles the decision on resume. Executors never approve.
 */
public final class GateExecutor implements StageExecutor {

    private final ApprovalKind kind;
    private final ApprovalService approvals;
    private final WorkflowStore store;

    private GateExecutor(ApprovalKind kind, ApprovalService approvals, WorkflowStore store) {
        this.kind = kind;
        this.approvals = approvals;
        this.store = store;
    }

    @Override
    public StageResult execute(RunContext context) {
        String requester = store.requesterKeyId(context.runId());
        ApprovalService.Approval approval = approvals.request(context.runId(), kind, context.node(), requester,
                kind + " required at " + context.node());
        return StageResult.awaitingApproval("approval " + approval.id() + " (" + kind.gate() + ") requested");
    }

    /** Creates gate executors for a kind. */
    @Component
    public static class Factory {

        private final ApprovalService approvals;
        private final WorkflowStore store;

        public Factory(ApprovalService approvals, WorkflowStore store) {
            this.approvals = approvals;
            this.store = store;
        }

        public GateExecutor gate(ApprovalKind kind) {
            return new GateExecutor(kind, approvals, store);
        }
    }
}
