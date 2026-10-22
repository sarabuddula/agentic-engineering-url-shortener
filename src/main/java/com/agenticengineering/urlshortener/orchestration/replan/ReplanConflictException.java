package com.agenticengineering.urlshortener.orchestration.replan;

/** A replan cannot be staged in the run's current state (409 REPLAN_CONFLICT). */
public class ReplanConflictException extends RuntimeException {

    public ReplanConflictException(String message) {
        super(message);
    }
}
