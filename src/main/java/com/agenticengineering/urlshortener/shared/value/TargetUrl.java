package com.agenticengineering.urlshortener.shared.value;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A redirect target that passed structural validation: surrounding whitespace stripped (FR-013), at most 2048
 * characters (contracts/openapi.yaml), an absolute HTTP or HTTPS URL (A-007) with a host. Address rules (D-003) and the
 * full canonical form are applied by the URL validation service (T029-T031), not by this value object.
 */
public final class TargetUrl {

    public static final int MAX_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final String value;

    private TargetUrl(String value) {
        this.value = value;
    }

    public static TargetUrl of(String input) {
        String candidate = input == null ? "" : input.strip();
        if (candidate.isEmpty()) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.BLANK, "target URL is empty");
        }
        if (candidate.length() > MAX_LENGTH) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.TOO_LONG,
                    candidate.length() + " characters exceeds " + MAX_LENGTH);
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.MALFORMED, e.getReason());
        }
        if (!uri.isAbsolute()) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.MALFORMED, "not an absolute URL");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.SCHEME_NOT_ALLOWED,
                    "scheme '" + uri.getScheme() + "' is not HTTP or HTTPS");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidTargetUrlException(InvalidTargetUrlException.Reason.MISSING_HOST, "URL has no host");
        }
        return new TargetUrl(candidate);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TargetUrl target && value.equals(target.value);
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
