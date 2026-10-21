package com.agenticengineering.urlshortener.shortener.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * T041: per-client token buckets (A-008a/b) with a bounded map. Eviction removes only clients idle long enough for a
 * full refill (removing them cannot restore restricted capacity); at the ceiling new clients are rejected and active
 * limits are kept (plan section 8).
 */
class ClientRateLimiterTest {

    private final MutableTimeSource clock = new MutableTimeSource(Instant.parse("2026-10-21T12:00:00Z"));

    @Test
    void burstIsAllowedThenRejectedWithARetryHint() {
        ClientRateLimiter limiter = new ClientRateLimiter(60, 3, 100, clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume("203.0.113.1").allowed()).isTrue();
        }
        ClientRateLimiter.Decision rejected = limiter.tryConsume("203.0.113.1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void clientsHaveSeparateBuckets() {
        ClientRateLimiter limiter = new ClientRateLimiter(60, 1, 100, clock);

        assertThat(limiter.tryConsume("203.0.113.1").allowed()).isTrue();
        assertThat(limiter.tryConsume("203.0.113.1").allowed()).isFalse();
        assertThat(limiter.tryConsume("203.0.113.2").allowed()).isTrue();
    }

    @Test
    void fullRefillTimeIsBurstDividedByRate() {
        assertThat(new ClientRateLimiter(60, 100, 10, clock).fullRefill()).isEqualTo(Duration.ofSeconds(100));
        assertThat(new ClientRateLimiter(600, 200, 10, clock).fullRefill()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void atTheCeilingANewClientIsRejectedAndActiveLimitsAreKept() {
        ClientRateLimiter limiter = new ClientRateLimiter(60, 1, 2, clock);
        limiter.tryConsume("203.0.113.1");
        limiter.tryConsume("203.0.113.2");

        assertThat(limiter.tryConsume("203.0.113.3").allowed()).as("new client at the ceiling").isFalse();
        assertThat(limiter.tryConsume("203.0.113.1").allowed()).as("active limit kept, not reset").isFalse();
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    void onlyClientsIdleForAFullRefillAreEvicted() {
        ClientRateLimiter limiter = new ClientRateLimiter(60, 1, 2, clock); // full refill after 1 s
        limiter.tryConsume("203.0.113.1");
        clock.advance(Duration.ofMillis(1500));
        limiter.tryConsume("203.0.113.2");

        assertThat(limiter.tryConsume("203.0.113.3").allowed()).as("203.0.113.1 idle >= refill time, evicted").isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);
        assertThat(limiter.tryConsume("203.0.113.2").allowed()).as("recently active client still limited").isFalse();
    }
}
