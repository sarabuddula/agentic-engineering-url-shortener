package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Random base-62 short codes (T033, ADR-006): no coordination and not guessable; uniqueness is the store's unique
 * constraint, not this class. The reserved top-level route words are never emitted.
 */
public class RandomBase62ShortCodeGenerator implements ShortCodeGenerator {

    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** First-level routes a code must never shadow (ADR-006), compared without case to stay on the safe side. */
    private static final Set<String> RESERVED = Set.of("api", "actuator");

    private final int length;
    private final RandomGenerator random;

    /** Production generator: length 8, SecureRandom. */
    public RandomBase62ShortCodeGenerator() {
        this(8, new SecureRandom());
    }

    public RandomBase62ShortCodeGenerator(int length, RandomGenerator random) {
        if (length < 4 || length > 16) {
            throw new IllegalArgumentException("short code length must be 4-16 (contract), was " + length);
        }
        this.length = length;
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public ShortCode next() {
        while (true) {
            StringBuilder code = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (!RESERVED.contains(code.toString().toLowerCase(Locale.ROOT))) {
                return ShortCode.of(code.toString());
            }
        }
    }
}
