package com.agenticengineering.urlshortener.orchestration.reliability;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** A FAILURE_EVENT row: the MTTR capture record (plan section 7). */
public record FailureEvent(long id, String runId, long nodeId, Instant failureDetectedAt, Instant recoveryStartAt,
        Instant recoveryCompleteAt, RecoveryMechanism mechanism, boolean recovered, Duration governanceWait,
        boolean mttrExcluded, String mttrExclusionReason) {

    /**
     * Individual recovery duration, from detection to recovery completion; empty for unrecovered failures, which are
     * reported separately and never enter the MTTR denominator. Governance wait is reported on its own.
     */
    public Optional<Duration> recoveryDuration() {
        return recovered ? Optional.of(Duration.between(failureDetectedAt, recoveryCompleteAt)) : Optional.empty();
    }
}
