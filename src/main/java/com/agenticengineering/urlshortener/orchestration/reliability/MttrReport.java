package com.agenticengineering.urlshortener.orchestration.reliability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregated MTTR (T079; plan section 7). {@code mttr} excludes governance wait (primary figure); {@code
 * mttrIncludingGovernanceWait} is the labelled secondary figure. Both are empty (N/A) without a recovered failure.
 * Demonstration-grade: measured on fault-injected demonstration runs, not production load (C-004).
 */
public record MttrReport(int recoveredCount, Duration totalRecovery, Optional<Duration> mttr, Duration totalGovernanceWait,
        Optional<Duration> mttrIncludingGovernanceWait, int unrecoveredCount, List<String> excluded, Map<String, Integer> byMechanism) {

    public String describe() {
        return "recovered failures: " + recoveredCount + "; total recovery: " + totalRecovery.toMillis() + " ms; MTTR: "
                + mttr.map(d -> d.toMillis() + " ms").orElse("N/A") + " (governance wait excluded); governance wait: "
                + totalGovernanceWait.toMillis() + " ms; MTTR including governance wait (secondary): "
                + mttrIncludingGovernanceWait.map(d -> d.toMillis() + " ms").orElse("N/A") + "; unrecovered failures (excluded): "
                + unrecoveredCount + "; excluded events: " + excluded.size() + "; by mechanism: " + byMechanism;
    }
}
