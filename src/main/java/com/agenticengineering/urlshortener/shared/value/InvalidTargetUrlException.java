package com.agenticengineering.urlshortener.shared.value;

/** A target URL failed structural validation (FR-002); {@link #reason()} is stable for error mapping and tests. */
public final class InvalidTargetUrlException extends IllegalArgumentException {

    /** Why the target was rejected; all map to the contract error code URL_REJECTED. */
    public enum Reason {
        BLANK, TOO_LONG, MALFORMED, SCHEME_NOT_ALLOWED, MISSING_HOST,
        // address rules (D-003, plan section 8), applied by the URL validation service
        USERINFO_NOT_ALLOWED, LOCALHOST_NAME, INTERNAL_ADDRESS, IPV4_MAPPED_IPV6, NUMERIC_HOST_FORM, UNRESOLVABLE_HOST
    }

    private final Reason reason;

    public InvalidTargetUrlException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
