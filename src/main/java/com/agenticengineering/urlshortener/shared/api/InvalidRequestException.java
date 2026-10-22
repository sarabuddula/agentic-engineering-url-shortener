package com.agenticengineering.urlshortener.shared.api;

/** A request that cannot be processed as sent (missing or malformed header or field): 400 INVALID_REQUEST. */
public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
