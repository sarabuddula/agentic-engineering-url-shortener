package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.time.Duration;
import java.time.Instant;

/** Controllable clock for tests (TimeSource port). */
public final class MutableTimeSource implements TimeSource {

    private Instant now;

    public MutableTimeSource(Instant start) {
        this.now = start;
    }

    @Override
    public synchronized Instant now() {
        return now;
    }

    public synchronized void advance(Duration by) {
        now = now.plus(by);
    }

    public synchronized void set(Instant instant) {
        now = instant;
    }
}
