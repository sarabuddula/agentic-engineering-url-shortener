package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.audit.PlatformAudit;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.agenticengineering.urlshortener.shortener.config.ShortenerProperties;
import com.agenticengineering.urlshortener.shortener.ratelimit.ClientRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP rate limits for link creation (A-008a) and redirect resolution (A-008b) (T041, FR-047). Runs after request
 * correlation and before any controller, so a rejected request allocates no short code and records no analytics.
 * The client is the direct peer address; forwarded headers are not trusted (A-008). Rejections answer 429 with
 * Retry-After and retry_after_seconds, are audited on the platform chain (route only, no client address) and counted
 * as a demonstration metric.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Pattern SINGLE_SEGMENT = Pattern.compile("/[^/]+");

    private final ClientRateLimiter creation;
    private final ClientRateLimiter redirect;
    private final PlatformAudit audit;
    private final MeterRegistry metrics;
    private final ObjectMapper json = new ObjectMapper();

    public RateLimitFilter(ShortenerProperties properties, TimeSource clock, PlatformAudit audit, MeterRegistry metrics) {
        ShortenerProperties.RateLimit limits = properties.rateLimit();
        this.creation = new ClientRateLimiter(limits.creationPerIp().requestsPerMinute(), limits.creationPerIp().burst(),
                limits.maxTrackedClients(), clock);
        this.redirect = new ClientRateLimiter(limits.redirectPerIp().requestsPerMinute(), limits.redirectPerIp().burst(),
                limits.maxTrackedClients(), clock);
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        ClientRateLimiter limiter = null;
        String limitName = null;
        if ("POST".equals(request.getMethod()) && "/api/v1/links".equals(path)) {
            limiter = creation;
            limitName = "creation-per-ip";
        } else if ("GET".equals(request.getMethod()) && SINGLE_SEGMENT.matcher(path).matches()) {
            limiter = redirect;
            limitName = "redirect-per-ip";
        }
        if (limiter != null) {
            ClientRateLimiter.Decision decision = limiter.tryConsume(request.getRemoteAddr());
            if (!decision.allowed()) {
                reject(request, response, limitName, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String limitName, long retryAfterSeconds)
            throws IOException {
        String route = request.getMethod() + " " + request.getRequestURI();
        try {
            audit.record("RATE_LIMITED", route, "REJECTED", "limit=" + limitName + " retry_after_seconds=" + retryAfterSeconds);
        } catch (RuntimeException e) {
            LOG.warn("platform audit of a rate-limit rejection failed cause={}", e.getClass().getSimpleName());
        }
        metrics.counter("app.ratelimit.rejections", "limit", limitName).increment();
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.rateLimited("Too many requests; retry after the indicated interval",
                RequestIds.of(request), (int) retryAfterSeconds);
        json.writeValue(response.getOutputStream(), body.toBody());
    }
}
