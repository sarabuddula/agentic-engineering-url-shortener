package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateRequestIdentityException;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateShortCodeException;
import com.agenticengineering.urlshortener.shortener.domain.NewLink;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import com.agenticengineering.urlshortener.shortener.persistence.JdbcLinkRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

/** T035: the SHORT_LINK repository maps the database's unique constraints to the domain exceptions (FR-016, FR-007). */
@JdbcTest
@Import(JdbcLinkRepository.class)
class JdbcLinkRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final TargetUrl TARGET = TargetUrl.of("https://example.com/a");
    private static final String HASH = "c".repeat(64);

    @Autowired
    private JdbcLinkRepository repository;

    @Test
    void insertedLinkIsFoundByCodeAndByRequestIdentity() {
        ShortLink stored = repository.insert(link("abcd", "key-00000001"));

        assertThat(stored.id()).isPositive();
        assertThat(repository.findByCode(ShortCode.of("abcd"))).contains(stored);
        assertThat(repository.findByRequestIdentity("key-00000001")).contains(stored);
        assertThat(repository.findByCode(ShortCode.of("zzzz"))).isEmpty();
    }

    @Test
    void duplicateCodeAndDuplicateIdentityMapToDistinctDomainExceptions() {
        repository.insert(link("abcd", "key-00000001"));

        assertThatThrownBy(() -> repository.insert(link("abcd", "key-00000002"))).isInstanceOf(DuplicateShortCodeException.class);
        assertThatThrownBy(() -> repository.insert(link("wxyz", "key-00000001")))
                .isInstanceOf(DuplicateRequestIdentityException.class);
    }

    private static NewLink link(String code, String identity) {
        return new NewLink(ShortCode.of(code), TARGET, identity, HASH, NOW, NOW.plusSeconds(3600));
    }
}
