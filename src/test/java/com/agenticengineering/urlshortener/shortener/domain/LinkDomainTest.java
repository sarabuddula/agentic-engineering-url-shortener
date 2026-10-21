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
import org.junit.jupiter.api.Test;

/**
 * T027: link creation and resolution in the framework-free domain (FR-001, FR-005, FR-006, FR-011, FR-014).
 * Expiry is derived at resolution: a link is expired from its expires_at instant onwards.
 */
class LinkDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    private static final TargetUrl TARGET = TargetUrl.of("https://example.com/docs");

    private final InMemoryLinkRepository links = new InMemoryLinkRepository();
    private final MutableTimeSource clock = new MutableTimeSource(NOW);
    private final LinkService service = new LinkService(links, new SequenceCodes("abcd", "efgh"), clock, DEFAULT_TTL);

    @Test
    void createReturnsALinkWithTheDefaultTtl() {
        ShortLink link = service.create("key-00000001", TARGET, null);

        assertThat(link.code()).isEqualTo(ShortCode.of("abcd"));
        assertThat(link.target()).isEqualTo(TARGET);
        assertThat(link.createdAt()).isEqualTo(NOW);
        assertThat(link.expiresAt()).isEqualTo(NOW.plus(DEFAULT_TTL));
        assertThat(link.requestIdentity()).isEqualTo("key-00000001");
        assertThat(link.payloadHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void createKeepsAnExplicitFutureExpiry() {
        Instant expiresAt = NOW.plus(Duration.ofHours(1));

        assertThat(service.create("key-00000001", TARGET, expiresAt).expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void createRejectsAnExpiryThatIsNotInTheFuture() {
        assertThatThrownBy(() -> service.create("key-00000001", TARGET, NOW)).isInstanceOf(InvalidExpirationException.class);
        assertThatThrownBy(() -> service.create("key-00000001", TARGET, NOW.minusSeconds(1)))
                .isInstanceOf(InvalidExpirationException.class);
        assertThat(links.size()).as("no short code allocated on rejection (FR-002)").isZero();
    }

    @Test
    void activeCodeResolvesToItsTarget() {
        service.create("key-00000001", TARGET, null);

        assertThat(service.resolve("abcd")).isInstanceOfSatisfying(Resolution.Redirect.class,
                redirect -> assertThat(redirect.target()).isEqualTo(TARGET));
    }

    @Test
    void codeIsExpiredFromItsExpiryInstantAndNeverRedirects() {
        ShortLink link = service.create("key-00000001", TARGET, null);
        clock.set(link.expiresAt().minusMillis(1));
        assertThat(service.resolve("abcd")).isInstanceOf(Resolution.Redirect.class);

        clock.set(link.expiresAt());
        assertThat(service.resolve("abcd")).isInstanceOfSatisfying(Resolution.Expired.class,
                expired -> assertThat(expired.link().code()).isEqualTo(ShortCode.of("abcd")));
    }

    @Test
    void unknownOrMalformedCodesAreNotFound() {
        assertThat(service.resolve("zzzz")).isEqualTo(new Resolution.NotFound("zzzz"));
        assertThat(service.resolve("a-b")).isEqualTo(new Resolution.NotFound("a-b"));
        assertThat(service.resolve(null)).isInstanceOf(Resolution.NotFound.class);
    }
}
