package com.agenticengineering.urlshortener.shortener.domain;

/** The store already binds this request identity to a link (unique constraint, FR-007). */
public final class DuplicateRequestIdentityException extends RuntimeException {

    public DuplicateRequestIdentityException(String requestIdentity) {
        super("request identity already bound");
    }
}
