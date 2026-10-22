package com.agenticengineering.urlshortener.shortener.domain;

/** Storage kept failing transiently after the bounded retries (FR-009): the defined 503, never a partial result. */
public final class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(int attempts, Throwable lastFailure) {
        super("storage unavailable after " + attempts + " attempts", lastFailure);
    }
}
