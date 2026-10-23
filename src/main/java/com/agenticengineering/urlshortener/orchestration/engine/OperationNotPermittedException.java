package com.agenticengineering.urlshortener.orchestration.engine;

/** An executor attempted an operation outside its node's whitelist (refused and audited; the stage fails). */
public class OperationNotPermittedException extends RuntimeException {

    public OperationNotPermittedException(String message) {
        super(message);
    }
}
