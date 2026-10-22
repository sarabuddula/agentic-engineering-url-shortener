package com.agenticengineering.urlshortener.orchestration.engine;

/** Workflow-run states (plan section 3). SAFE_STOPPED is resumable; COMPLETED and TERMINATED are terminal. */
public enum RunStatus {
    RUNNING, AWAITING_APPROVAL, SUSPENDED_CLARIFICATION, SAFE_STOPPED, COMPLETED, TERMINATED;

    public boolean isTerminal() {
        return this == COMPLETED || this == TERMINATED;
    }
}
