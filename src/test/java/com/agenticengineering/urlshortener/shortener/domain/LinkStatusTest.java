package com.agenticengineering.urlshortener.shortener.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.tests.support.InMemoryLinkRepository;
import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import com.agenticengineering.urlshortener.tests.support.SequenceCodes;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SCN-A link status lookup (plan section 11; run run-934a50a1-35b3-4b72-85a3-4c66625a9969, task t4): whether a short
 * code is active, expired or unknown, with its expiry time. Expiry is derived from expires_at at lookup time, exactly
 * as for redirects (FR-006). Requirements: SCN-A, FR-040, FR-006.
 */
class LinkStatusTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private final MutableTimeSource clock = new MutableTimeSource(NOW);
    private final LinkService service = new LinkService(new InMemoryLinkRepository(), new SequenceCodes("abcd", "efgh"), clock,
            Duration.ofDays(7));

    @Test
    void anUnexpiredLinkIsActiveWithItsExpiry() {
        ShortLink link = service.create("key-00000001", TargetUrl.of("https://example.com/a"), NOW.plus(Duration.ofHours(1)));

        LinkStatus status = service.status(link.code().value());

        assertThat(status.state()).isEqualTo(LinkStatus.State.ACTIVE);
        assertThat(status.shortCode()).isEqualTo("abcd");
        assertThat(status.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void aLinkIsExpiredFromItsExpiryInstantOnwards() {
        ShortLink link = service.create("key-00000001", TargetUrl.of("https://example.com/a"), NOW.plus(Duration.ofHours(1)));
        clock.set(NOW.plus(Duration.ofHours(1)));

        LinkStatus status = service.status(link.code().value());

        assertThat(status.state()).isEqualTo(LinkStatus.State.EXPIRED);
        assertThat(status.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void anUnknownOrMalformedCodeIsUnknownWithoutExpiry() {
        assertThat(service.status("zzzz")).isEqualTo(new LinkStatus("zzzz", LinkStatus.State.UNKNOWN, null));
        assertThat(service.status("not a code!")).isEqualTo(new LinkStatus("not a code!", LinkStatus.State.UNKNOWN, null));
    }
}
