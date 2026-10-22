package com.agenticengineering.urlshortener.shared.api;

/** A valid key lacking a role the endpoint requires (FR-045): 403 FORBIDDEN, already audited by the caller. */
public final class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
