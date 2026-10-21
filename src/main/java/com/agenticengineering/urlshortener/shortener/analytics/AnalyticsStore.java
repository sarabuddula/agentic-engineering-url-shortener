package com.agenticengineering.urlshortener.shortener.analytics;

import java.time.Instant;

/** Persistence port for redirect analytics (ADR-003). */
public interface AnalyticsStore {

    void insert(RedirectEvent event);

    /** @return the new LOSS_REPORT id */
    long insertLossReport(Instant periodStart, Instant periodEnd, long unrecordedCount);

    /** @param code null for all codes */
    Summary summary(String code);
}
