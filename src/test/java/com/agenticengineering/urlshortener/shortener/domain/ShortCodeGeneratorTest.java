package com.agenticengineering.urlshortener.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.tests.support.InMemoryLinkRepository;
import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import com.agenticengineering.urlshortener.tests.support.SequenceCodes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * T032: random base-62 codes (ADR-006, FR-004, FR-016). Generated length 8 (the contract example's length; minimum 4);
 * reserved route words never emitted; a collision regenerates at most 3 times (the approved retry bound of PVT-004).
 */
class ShortCodeGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final TargetUrl TARGET = TargetUrl.of("https://example.com/a");

    @Test
    void generatesBase62CodesOfTheConfiguredLength() {
        RandomBase62ShortCodeGenerator generator = new RandomBase62ShortCodeGenerator(8, new Random(42));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = generator.next().value();
            assertThat(code).matches("[A-Za-z0-9]{8}");
            seen.add(code);
        }
        assertThat(seen).as("random, not sequential or repeating").hasSize(1000);
    }

    @Test
    void lengthBelowTheMinimumOfFourIsRejected() {
        assertThatThrownBy(() -> new RandomBase62ShortCodeGenerator(3, new Random(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedRouteWordsAreNeverEmitted() {
        RandomBase62ShortCodeGenerator generator =
                new RandomBase62ShortCodeGenerator(8, scripted("actuator", "ACTUATOR", "abcd1234"));

        assertThat(generator.next().value()).isEqualTo("abcd1234");
    }

    @Test
    void aCollisionRegeneratesWithAFreshCode() {
        InMemoryLinkRepository links = new InMemoryLinkRepository();
        LinkService service = new LinkService(links, new SequenceCodes("abcd", "abcd", "abcd", "efgh"), new MutableTimeSource(NOW),
                Duration.ofDays(7));
        service.create("key-00000001", TARGET, null);

        assertThat(service.create("key-00000002", TARGET, null).code()).isEqualTo(ShortCode.of("efgh"));
    }

    @Test
    void collisionsBeyondTheBoundFailWithoutStoringALink() {
        InMemoryLinkRepository links = new InMemoryLinkRepository();
        LinkService service = new LinkService(links, new SequenceCodes("abcd", "abcd", "abcd", "abcd", "abcd"),
                new MutableTimeSource(NOW), Duration.ofDays(7));
        service.create("key-00000001", TARGET, null);

        assertThatThrownBy(() -> service.create("key-00000002", TARGET, null)).isInstanceOf(ShortCodeCollisionException.class);
        assertThat(links.size()).isEqualTo(1);
    }

    @Test
    void anExpiredCodeIsNeverReassigned() {
        InMemoryLinkRepository links = new InMemoryLinkRepository();
        MutableTimeSource clock = new MutableTimeSource(NOW);
        LinkService service = new LinkService(links, new SequenceCodes("abcd", "abcd", "wxyz"), clock, Duration.ofDays(7));
        service.create("key-00000001", TARGET, null);
        clock.advance(Duration.ofDays(8));

        ShortLink second = service.create("key-00000002", TargetUrl.of("https://example.com/other"), null);

        assertThat(second.code()).isEqualTo(ShortCode.of("wxyz"));
        assertThat(service.resolve("abcd")).isInstanceOfSatisfying(Resolution.Expired.class,
                expired -> assertThat(expired.link().target()).isEqualTo(TARGET));
    }

    /** RandomGenerator that spells the given codes, one alphabet index per nextInt call. */
    private static RandomGenerator scripted(String... codes) {
        Deque<Integer> indices = new ArrayDeque<>();
        for (String code : codes) {
            for (char c : code.toCharArray()) {
                indices.add(RandomBase62ShortCodeGenerator.ALPHABET.indexOf(c));
            }
        }
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                return indices.poll();
            }
        };
    }
}
