package com.agenticengineering.urlshortener.orchestration.stages;

/** Decomposition was requested while a material ambiguity is unresolved: unsafe implementation is prevented (FR-035). */
public final class OpenAmbiguityException extends IllegalStateException {

    public OpenAmbiguityException(int openFindings) {
        super(openFindings + " material finding(s) unresolved; clarification (N03, gate G1) must be decided first");
    }
}
