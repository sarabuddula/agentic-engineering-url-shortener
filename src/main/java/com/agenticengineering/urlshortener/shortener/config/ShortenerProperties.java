package com.agenticengineering.urlshortener.shortener.config;

import com.agenticengineering.urlshortener.shared.dns.ResolverMode;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-plane settings under {@code app} (quickstart configuration table). */
@ConfigurationProperties("app")
public record ShortenerProperties(Dns dns, RateLimit rateLimit, int defaultTtlDays, PersistenceRetry persistenceRetry) {

    /** {@code app.persistence-retry}: bounded retry of transient storage failures (FR-009, A-013). */
    public record PersistenceRetry(int maxRetries, Duration initialBackoff, double jitter) {
    }

    /** {@code app.dns}: resolver mode, the live lookup timeout (A-015) and the fixed-local table location. */
    public record Dns(ResolverMode resolverMode, Duration lookupTimeout, Path fixedLocalTable) {
    }

    /**
     * {@code app.rate-limit}: per-IP limits for creation (A-008a) and redirect (A-008b), and the ceiling on tracked
     * clients per limit (plan section 8 bounded map).
     */
    public record RateLimit(Limit creationPerIp, Limit redirectPerIp, int maxTrackedClients) {
    }

    public record Limit(int requestsPerMinute, int burst) {
    }
}
