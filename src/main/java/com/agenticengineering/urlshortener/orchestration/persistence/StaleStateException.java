package com.agenticengineering.urlshortener.orchestration.persistence;

/** The stored state or version no longer matches what the caller expected (optimistic lock); nothing was changed. */
public final class StaleStateException extends RuntimeException {

    public StaleStateException(String message) {
        super(message);
    }
}
