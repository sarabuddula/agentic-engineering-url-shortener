package com.agenticengineering.urlshortener.shared.value;

import java.util.Objects;
import java.util.regex.Pattern;

/** A short code: 4-16 base-62 characters (contracts/openapi.yaml, ADR-006). Case-sensitive; never reassigned (FR-004). */
public final class ShortCode {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9]{4,16}");

    private final String value;

    private ShortCode(String value) {
        this.value = value;
    }

    public static ShortCode of(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidShortCodeException(value);
        }
        return new ShortCode(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShortCode code && value.equals(code.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
