package com.agenticengineering.urlshortener.shared.port;

/** ID generation port for run, operation and event identifiers; deterministic implementations are used in tests. */
@FunctionalInterface
public interface IdGenerator {

    String newId();
}
