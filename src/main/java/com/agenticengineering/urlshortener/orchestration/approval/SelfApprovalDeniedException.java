package com.agenticengineering.urlshortener.orchestration.approval;

/** Self-approval or agent approval (FR-046): 403 SELF_APPROVAL_DENIED; the approval stays pending; already audited. */
public final class SelfApprovalDeniedException extends RuntimeException {

    public SelfApprovalDeniedException(String message) {
        super(message);
    }
}
