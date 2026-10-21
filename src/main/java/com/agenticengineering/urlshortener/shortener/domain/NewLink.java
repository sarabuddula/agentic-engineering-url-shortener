package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.time.Instant;

/** A link to be inserted; the repository assigns the id. */
public record NewLink(ShortCode code, TargetUrl target, String requestIdentity, String payloadHash, Instant createdAt,
        Instant expiresAt) {
}
