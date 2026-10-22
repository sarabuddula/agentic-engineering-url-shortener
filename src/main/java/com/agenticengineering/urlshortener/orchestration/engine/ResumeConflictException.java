package com.agenticengineering.urlshortener.orchestration.engine;

/** The run cannot be resumed: it is not safe-stopped, or the cause of the stop is not addressed (409). */
public class ResumeConflictException extends RuntimeException {

    public ResumeConflictException(String message) {
        super(message);
    }
}
