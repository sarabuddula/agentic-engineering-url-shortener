package com.agenticengineering.urlshortener.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.tests.support.InMemoryLinkRepository;
import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import com.agenticengineering.urlshortener.tests.support.SequenceCodes;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * T028: request idempotency (FR-007 sub-outcomes a and c, ADR-006, approved spec delta 409). The request identity is
 * the Idempotency-Key; the payload is the canonical target plus the requested expiry (absent stays absent).
 */
class IdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final TargetUrl TARGET = TargetUrl.of("https://example.com/docs");

    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final IdempotencyService service = new IdempotencyService(
            new LinkService(links, new SequenceCodes("abcd", "efgh", "ijkl"), new MutableTimeSource(NOW), Duration.ofDays(7)),
            links);

    @Test
    void firstRequestCreatesALink() {
        IdempotencyService.Outcome outcome = service.createOrReplay("key-00000001", TARGET, null);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.link().code().value()).isEqualTo("abcd");
    }

    @Test
    void sameIdentityAndSamePayloadReplaysTheSameLink() {
        IdempotencyService.Outcome first = service.createOrReplay("key-00000001", TARGET, null);
        IdempotencyService.Outcome replay = service.createOrReplay("key-00000001", TargetUrl.of("  https://example.com/docs "), null);

        assertThat(replay.created()).isFalse();
        assertThat(replay.link()).isEqualTo(first.link());
        assertThat(links.size()).isEqualTo(1);
    }

    @Test
    void sameIdentityWithADifferentTargetIsAConflictAndChangesNothing() {
        IdempotencyService.Outcome first = service.createOrReplay("key-00000001", TARGET, null);

        assertThatThrownBy(() -> service.createOrReplay("key-00000001", TargetUrl.of("https://example.com/other"), null))
                .isInstanceOf(RequestIdentityConflictException.class);
        assertThat(links.findByRequestIdentity("key-00000001")).contains(first.link());
        assertThat(links.size()).isEqualTo(1);
    }

    @Test
    void sameIdentityWithADifferentExpiryIsAConflict() {
        service.createOrReplay("key-00000001", TARGET, null);

        assertThatThrownBy(() -> service.createOrReplay("key-00000001", TARGET, NOW.plus(Duration.ofHours(1))))
                .isInstanceOf(RequestIdentityConflictException.class);
    }

    @Test
    void independentRequestsForTheSameUrlCreateSeparateLinks() {
        IdempotencyService.Outcome a = service.createOrReplay("key-00000001", TARGET, null);
        IdempotencyService.Outcome b = service.createOrReplay("key-00000002", TARGET, null);

        assertThat(a.created()).isTrue();
        assertThat(b.created()).isTrue();
        assertThat(a.link().code()).isNotEqualTo(b.link().code());
        assertThat(links.size()).isEqualTo(2);
    }
}
