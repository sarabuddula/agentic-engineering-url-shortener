package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.time.Instant;

/** A stored short link (SHORT_LINK). Expiry status is derived, never stored (data-model.md). */
public record ShortLink(long id, ShortCode code, TargetUrl target, String requestIdentity, String payloadHash,
        Instant createdAt, Instant expiresAt) {

    /** A link is expired from its expires_at instant onwards. */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
