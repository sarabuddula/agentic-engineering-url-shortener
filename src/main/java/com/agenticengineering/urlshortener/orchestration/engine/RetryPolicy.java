package com.agenticengineering.urlshortener.orchestration.engine;

import java.time.Duration;

/**
 * FP-1 bounds (plan section 6, PVT-004): at most {@code maxRetries} retries after the initial attempt, exponential backoff
 * from {@code baseDelay} (base, 2 x base, 4 x base), and a deterministic bound after which the automated path stops
 * retrying. The bound is separate from human approval windows (PVT-005).
 */
public record RetryPolicy(int maxRetries, Duration baseDelay, Duration deadline) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(60));

    /** Delay before retry number {@code retry} (1-based). */
    public Duration delay(int retry) {
        return baseDelay.multipliedBy(1L << (retry - 1));
    }
}
