package com.agenticengineering.urlshortener.orchestration.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * One audit fact (Constitution IX): actor type, action, timestamp, affected artifact or state, result, reason.
 * Sequence and hashes are assigned by {@link AuditChain} on append.
 */
public record AuditEntry(ActorType actorType, String action, Instant occurredAt, String affectedArtifact, String result,
        String reason) {

    public enum ActorType { HUMAN, AGENT, SYSTEM }

    public AuditEntry {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(result, "result");
    }
}
