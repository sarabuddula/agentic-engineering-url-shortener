package com.agenticengineering.urlshortener.orchestration.engine;

/** A state change the data model prohibits; callers audit the blocked attempt (SC-004). */
public final class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(Enum<?> from, Enum<?> to) {
        super("illegal transition " + from + " -> " + to);
    }
}
