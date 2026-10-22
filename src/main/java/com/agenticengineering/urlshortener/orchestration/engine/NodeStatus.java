package com.agenticengineering.urlshortener.orchestration.engine;

/**
 * Stage-node states (data-model.md "State Transitions"). HELD (replan staging) is not modelled: staged revisions are
 * deferred (L-SC-03). Fallback reuses FAILED -> RETRY_WAITING -> RUNNING with a recorded cause, so it needs no state.
 */
public enum NodeStatus {
    PENDING, READY, RUNNING, AWAITING_APPROVAL, RETRY_WAITING, FAILED, SAFE_STOPPED, SUCCEEDED, NOT_APPLICABLE;

    /** No exit is allowed from a terminal node state. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == NOT_APPLICABLE;
    }
}
