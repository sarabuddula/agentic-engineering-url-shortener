package com.agenticengineering.urlshortener.shortener.domain;

/** A requested expiry is not in the future (FR-011); no short code is allocated (FR-002). */
public final class InvalidExpirationException extends IllegalArgumentException {

    public InvalidExpirationException(String message) {
        super(message);
    }
}
