package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shortener.domain.ShortCodeGenerator;
import java.util.ArrayDeque;
import java.util.Deque;

/** Deterministic ShortCodeGenerator for tests: hands out the given codes in order. */
public final class SequenceCodes implements ShortCodeGenerator {

    private final Deque<String> codes = new ArrayDeque<>();

    public SequenceCodes(String... codes) {
        for (String code : codes) {
            this.codes.add(code);
        }
    }

    @Override
    public synchronized ShortCode next() {
        if (codes.isEmpty()) {
            throw new IllegalStateException("test ran out of short codes");
        }
        return ShortCode.of(codes.poll());
    }
}
