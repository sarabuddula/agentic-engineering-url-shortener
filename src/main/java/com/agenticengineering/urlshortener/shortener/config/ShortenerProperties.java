package com.agenticengineering.urlshortener.shortener.config;

import com.agenticengineering.urlshortener.shared.dns.ResolverMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application-plane settings under {@code app} (quickstart configuration table). */
@ConfigurationProperties("app")
public record ShortenerProperties(Dns dns, RateLimit rateLimit, int defaultTtlDays) {

    /** {@code app.dns}: resolver mode and the live lookup timeout (A-015). */
    public record Dns(ResolverMode resolverMode, Duration lookupTimeout) {
    }

    /** {@code app.rate-limit}: per-IP limits for creation (A-008a) and redirect (A-008b). */
    public record RateLimit(Limit creationPerIp, Limit redirectPerIp) {
    }

    public record Limit(int requestsPerMinute, int burst) {
    }
}
