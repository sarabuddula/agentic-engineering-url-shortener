package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * SHA-256 of the normalized creation payload (FR-007, data-model payload_hash): the canonical target and the requested
 * expiry as given; an absent expiry stays absent, so a replay without one matches the original.
 */
public final class PayloadHash {

    private PayloadHash() {
    }

    public static String of(TargetUrl target, Instant requestedExpiresAt) {
        String normalized = "target_url=" + target.value() + "\nexpires_at=" + (requestedExpiresAt == null ? "-" : requestedExpiresAt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
