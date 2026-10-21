package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;

/** The store already holds this short code (unique constraint). */
public final class DuplicateShortCodeException extends RuntimeException {

    public DuplicateShortCodeException(ShortCode code) {
        super("short code already taken: " + code);
    }
}
