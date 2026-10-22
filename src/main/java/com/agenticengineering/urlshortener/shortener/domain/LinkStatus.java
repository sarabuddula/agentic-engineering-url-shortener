package com.agenticengineering.urlshortener.shortener.domain;

import java.time.Instant;

/**
 * Status of a short code at lookup time (SCN-A): ACTIVE or EXPIRED with the link's expiry, or UNKNOWN (no expiry) for
 * a code that is not stored or not well-formed. Expiry is derived from expires_at, never stored (data-model.md).
 */
public record LinkStatus(String shortCode, State state, Instant expiresAt) {

    public enum State { ACTIVE, EXPIRED, UNKNOWN }
}
