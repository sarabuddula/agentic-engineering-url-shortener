package com.agenticengineering.urlshortener.shared.port;

import java.time.Instant;

/** Clock port: every time-dependent rule (expiry, timeouts, MTTR timestamps) reads time here so tests can control it. */
@FunctionalInterface
public interface TimeSource {

    Instant now();
}
