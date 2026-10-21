package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;

/** Outcome of resolving a short code (FR-005, FR-006, FR-014). Only {@link Redirect} carries a target. */
public sealed interface Resolution {

    record Redirect(TargetUrl target, ShortLink link) implements Resolution {
    }

    record Expired(ShortLink link) implements Resolution {
    }

    record NotFound(String attemptedCode) implements Resolution {
    }
}
