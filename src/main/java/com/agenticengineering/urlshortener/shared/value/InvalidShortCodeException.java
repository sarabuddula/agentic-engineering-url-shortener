package com.agenticengineering.urlshortener.shared.value;

/** The input is not a well-formed short code (4-16 base-62 characters). */
public final class InvalidShortCodeException extends IllegalArgumentException {

    public InvalidShortCodeException(String input) {
        super("not a valid short code: " + (input == null ? "null" : "'" + input + "'"));
    }
}
