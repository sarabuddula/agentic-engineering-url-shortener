package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Link creation and resolution (T030; FR-001, FR-005, FR-006, FR-011, FR-014). Framework-free (NFR-004). The target is
 * expected to be validated and canonical already (UrlValidator, T031); idempotency wraps this service (T034).
 */
public class LinkService {

    private final LinkRepository links;
    private final ShortCodeGenerator codes;
    private final TimeSource clock;
    private final Duration defaultTtl;

    public LinkService(LinkRepository links, ShortCodeGenerator codes, TimeSource clock, Duration defaultTtl) {
        this.links = Objects.requireNonNull(links);
        this.codes = Objects.requireNonNull(codes);
        this.clock = Objects.requireNonNull(clock);
        this.defaultTtl = Objects.requireNonNull(defaultTtl);
    }

    /**
     * Creates a link that expires at {@code requestedExpiresAt}, or after the default TTL (A-006) when it is null.
     *
     * @throws InvalidExpirationException when the requested expiry is not in the future
     */
    public ShortLink create(String requestIdentity, TargetUrl target, Instant requestedExpiresAt) {
        Instant now = clock.now();
        if (requestedExpiresAt != null && !requestedExpiresAt.isAfter(now)) {
            throw new InvalidExpirationException("expires_at must be in the future");
        }
        Instant expiresAt = requestedExpiresAt != null ? requestedExpiresAt : now.plus(defaultTtl);
        return links.insert(new NewLink(codes.next(), target, requestIdentity, PayloadHash.of(target, requestedExpiresAt),
                now, expiresAt));
    }

    public Resolution resolve(String rawCode) {
        Optional<ShortLink> link;
        try {
            link = links.findByCode(ShortCode.of(rawCode));
        } catch (InvalidShortCodeException malformed) {
            return new Resolution.NotFound(rawCode);
        }
        if (link.isEmpty()) {
            return new Resolution.NotFound(rawCode);
        }
        if (link.get().isExpiredAt(clock.now())) {
            return new Resolution.Expired(link.get());
        }
        return new Resolution.Redirect(link.get().target(), link.get());
    }
}
