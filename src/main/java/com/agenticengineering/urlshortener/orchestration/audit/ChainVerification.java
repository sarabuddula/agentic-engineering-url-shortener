package com.agenticengineering.urlshortener.orchestration.audit;

/**
 * Result of verifying a run's audit chain (FR-048): intact with the number of events checked, or broken at a 1-based
 * position with the failed check. Detects tampering for the stated checks; it does not prevent it (A-011).
 */
public record ChainVerification(boolean intact, long eventCount, Long failedPosition, Check check) {

    public enum Check {
        /** The recomputed hash differs from the stored one: the event was altered. */
        HASH_MISMATCH,
        /** The event does not link to its predecessor's hash: events were reordered or replaced. */
        PREDECESSOR_MISMATCH,
        /** A sequence number is missing: an event was removed. */
        SEQUENCE_GAP,
        /** Fewer events than the independently retained count on the run: the tail was truncated. */
        TRUNCATION,
        /** The chain is internally consistent but disagrees with the run's retained head or count. */
        HEAD_MISMATCH
    }

    public static ChainVerification intact(long eventCount) {
        return new ChainVerification(true, eventCount, null, null);
    }

    public static ChainVerification broken(long position, Check check) {
        return new ChainVerification(false, 0, position, check);
    }
}
