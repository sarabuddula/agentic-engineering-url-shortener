package com.agenticengineering.urlshortener.shortener.domain;

/** Every bounded regeneration collided with an existing code; no link was stored (FR-002). */
public final class ShortCodeCollisionException extends RuntimeException {

    public ShortCodeCollisionException(int attempts) {
        super("no free short code after " + attempts + " attempts");
    }
}
