package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;

/** Source of candidate short codes (ADR-006); uniqueness is enforced by the store, not by the generator. */
@FunctionalInterface
public interface ShortCodeGenerator {

    ShortCode next();
}
