package com.agenticengineering.urlshortener.shortener.analytics;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous redirect analytics with quantified loss (T039; FR-012, FR-015, plan section 2). A failed capture never
 * fails the redirect: it is logged and counted, and {@link #checkpoint()} writes the count to LOSS_REPORT. Disclosed
 * limitation (plan): a crash between a failure and the next checkpoint loses that count.
 */
public class RedirectAnalytics {

    private static final Logger LOG = LoggerFactory.getLogger(RedirectAnalytics.class);

    private final AnalyticsStore store;
    private final TimeSource clock;
    private final AtomicLong unrecorded = new AtomicLong();
    private Instant periodStart;

    public RedirectAnalytics(AnalyticsStore store, TimeSource clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.periodStart = clock.now();
    }

    public void record(Long shortLinkId, String attemptedCode, RedirectEvent.Outcome outcome) {
        String code = attemptedCode == null ? "" : attemptedCode;
        if (code.length() > RedirectEvent.MAX_ATTEMPTED_CODE_LENGTH) {
            code = code.substring(0, RedirectEvent.MAX_ATTEMPTED_CODE_LENGTH);
        }
        try {
            store.insert(new RedirectEvent(shortLinkId, code, clock.now(), outcome));
        } catch (RuntimeException e) {
            unrecorded.incrementAndGet();
            LOG.warn("analytics capture failed outcome={} cause={} - counted as unrecorded", outcome, e.getClass().getSimpleName());
        }
    }

    /** Writes the unrecorded count for the period since the last checkpoint; writes nothing when there was no loss. */
    public synchronized void checkpoint() {
        Instant now = clock.now();
        long count = unrecorded.getAndSet(0);
        if (count > 0) {
            try {
                store.insertLossReport(periodStart, now, count);
            } catch (RuntimeException e) {
                unrecorded.addAndGet(count); // keep the loss for the next checkpoint
                LOG.warn("loss checkpoint failed cause={} - {} unrecorded events carried over", e.getClass().getSimpleName(), count);
                return;
            }
        }
        periodStart = now;
    }

    /** Losses counted since the last successful checkpoint (not yet durable). */
    public long pendingUnrecorded() {
        return unrecorded.get();
    }

    public Summary summary(String code) {
        return store.summary(code);
    }
}
