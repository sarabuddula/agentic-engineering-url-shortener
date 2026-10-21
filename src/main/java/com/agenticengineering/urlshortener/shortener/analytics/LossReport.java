package com.agenticengineering.urlshortener.shortener.analytics;

import java.time.Instant;

/** A LOSS_REPORT checkpoint: events that could not be recorded in a period (FR-015). */
public record LossReport(long id, Instant periodStart, Instant periodEnd, long unrecordedCount) {
}
