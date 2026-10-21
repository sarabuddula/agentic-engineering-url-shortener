package com.agenticengineering.urlshortener.shortener.domain;

/** The request identity is already bound to a different normalized payload: 409, no overwrite (approved spec delta). */
public final class RequestIdentityConflictException extends RuntimeException {

    public RequestIdentityConflictException() {
        super("Idempotency key already bound to a different payload");
    }
}
