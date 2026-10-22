package com.agenticengineering.urlshortener.orchestration.reliability;

/** A rollback was attempted on an operation classed as irreversible (FR-027, FR-049); compensation applies instead. */
public class IrreversibleOperationException extends RuntimeException {

    public IrreversibleOperationException(String message) {
        super(message);
    }
}
