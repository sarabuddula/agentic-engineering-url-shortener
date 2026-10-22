package com.agenticengineering.urlshortener.orchestration.engine;

/**
 * STAGE_EDGE kinds (data-model.md). DEPENDENCY: the target waits for the source. SYNC: a join that waits for every
 * incoming branch. CONDITIONAL: the target runs only if the source selects it; otherwise it is NOT_APPLICABLE.
 */
public enum EdgeKind { DEPENDENCY, SYNC, CONDITIONAL }
