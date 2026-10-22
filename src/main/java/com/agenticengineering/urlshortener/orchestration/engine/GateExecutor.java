package com.agenticengineering.urlshortener.orchestration.engine;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
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
    private final ArtifactStore artifacts;

    private GateExecutor(ApprovalKind kind, ApprovalService approvals, WorkflowStore store, ArtifactStore artifacts) {
        this.kind = kind;
        this.approvals = approvals;
        this.store = store;
        this.artifacts = artifacts;
    }

    @Override
    public StageResult execute(RunContext context) {
        if (kind == ApprovalKind.DESIGN_APPROVAL) {
            // the G2 decision is taken on this design version; a later revision is a replan trigger (T089, FR-029)
            artifacts.readIfPresent(context.runId(), "DESIGN", context.node());
        }
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
        private final ArtifactStore artifacts;

        public Factory(ApprovalService approvals, WorkflowStore store, ArtifactStore artifacts) {
            this.approvals = approvals;
            this.store = store;
            this.artifacts = artifacts;
        }

        public GateExecutor gate(ApprovalKind kind) {
            return new GateExecutor(kind, approvals, store, artifacts);
        }
    }
}
