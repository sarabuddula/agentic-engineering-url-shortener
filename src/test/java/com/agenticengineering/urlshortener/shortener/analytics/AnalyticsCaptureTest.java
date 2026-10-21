package com.agenticengineering.urlshortener.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T039: synchronous capture with quantified loss (FR-012, FR-015, plan section 2 analytics failure observability).
 * A failed capture never fails the redirect; it is counted and written to a LOSS_REPORT at the next checkpoint.
 */
class AnalyticsCaptureTest {

    private static final Instant T0 = Instant.parse("2026-10-21T12:00:00Z");

    /** In-memory store whose event writes can be made to fail. */
    static final class FakeStore implements AnalyticsStore {
        final List<RedirectEvent> events = new ArrayList<>();
        final List<LossReport> lossReports = new ArrayList<>();
        boolean failWrites;

        @Override
        public void insert(RedirectEvent event) {
            if (failWrites) {
                throw new IllegalStateException("storage unavailable");
            }
            events.add(event);
        }

        @Override
        public long insertLossReport(Instant periodStart, Instant periodEnd, long unrecordedCount) {
            lossReports.add(new LossReport(lossReports.size() + 1, periodStart, periodEnd, unrecordedCount));
            return lossReports.size();
        }

        @Override
        public Summary summary(String code) {
            throw new UnsupportedOperationException();
        }
    }

    private final FakeStore store = new FakeStore();
    private final MutableTimeSource clock = new MutableTimeSource(T0);
    private final RedirectAnalytics analytics = new RedirectAnalytics(store, clock);

    @Test
    void eachOutcomeIsRecordedSynchronouslyWithoutPersonalData() {
        analytics.record(7L, "abcd1234", RedirectEvent.Outcome.REDIRECTED);
        analytics.record(7L, "abcd1234", RedirectEvent.Outcome.EXPIRED);
        analytics.record(null, "nope1234", RedirectEvent.Outcome.NOT_FOUND);

        assertThat(store.events).containsExactly(
                new RedirectEvent(7L, "abcd1234", T0, RedirectEvent.Outcome.REDIRECTED),
                new RedirectEvent(7L, "abcd1234", T0, RedirectEvent.Outcome.EXPIRED),
                new RedirectEvent(null, "nope1234", T0, RedirectEvent.Outcome.NOT_FOUND));
        assertThat(RedirectEvent.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("shortLinkId", "attemptedCode", "occurredAt", "outcome");
    }

    @Test
    void attemptedCodesAreCappedToTheColumnLength() {
        analytics.record(null, "x".repeat(500), RedirectEvent.Outcome.NOT_FOUND);

        assertThat(store.events.get(0).attemptedCode()).hasSize(RedirectEvent.MAX_ATTEMPTED_CODE_LENGTH);
    }

    @Test
    void captureFailureIsCountedAndNeverPropagates() {
        store.failWrites = true;

        assertThatCode(() -> analytics.record(7L, "abcd1234", RedirectEvent.Outcome.REDIRECTED)).doesNotThrowAnyException();
        assertThatCode(() -> analytics.record(7L, "abcd1234", RedirectEvent.Outcome.REDIRECTED)).doesNotThrowAnyException();
        assertThat(analytics.pendingUnrecorded()).isEqualTo(2);
    }

    @Test
    void checkpointWritesTheLossWithItsPeriodAndResetsTheCounter() {
        store.failWrites = true;
        analytics.record(7L, "abcd1234", RedirectEvent.Outcome.REDIRECTED);
        analytics.record(null, "nope1234", RedirectEvent.Outcome.NOT_FOUND);
        clock.advance(Duration.ofMinutes(1));

        analytics.checkpoint();

        assertThat(store.lossReports).containsExactly(new LossReport(1, T0, T0.plus(Duration.ofMinutes(1)), 2));
        assertThat(analytics.pendingUnrecorded()).isZero();
    }

    @Test
    void checkpointWithoutLossWritesNothingButAdvancesThePeriod() {
        clock.advance(Duration.ofMinutes(1));
        analytics.checkpoint();
        store.failWrites = true;
        analytics.record(7L, "abcd1234", RedirectEvent.Outcome.REDIRECTED);
        clock.advance(Duration.ofMinutes(1));
        analytics.checkpoint();

        assertThat(store.lossReports).containsExactly(
                new LossReport(1, T0.plus(Duration.ofMinutes(1)), T0.plus(Duration.ofMinutes(2)), 1));
    }
}
