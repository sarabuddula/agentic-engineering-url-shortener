package com.agenticengineering.urlshortener.orchestration.approval;

/** The approval window elapsed through escalation (PVT-005): 410; the affected path is safe-stopped. */
public final class ApprovalExpiredException extends RuntimeException {

    public ApprovalExpiredException(long approvalId) {
        super("approval " + approvalId + " expired after its escalation window");
    }
}
