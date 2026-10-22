package com.agenticengineering.urlshortener.shortener.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateShortCodeException;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.NewLink;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import com.agenticengineering.urlshortener.shortener.domain.StorageUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * T102: persistence failure classification and bounded retry (FR-009, NFR-002, PVT-004, plan section 6). Transient
 * (connection failure, query timeout): at most 3 retries with backoff 1 s, 2 s, 4 s +/-20% (A-013), then
 * StorageUnavailableException. Permanent: no retry, rethrown. Domain exceptions pass through untouched.
 */
class RetryingLinkRepositoryTest {

    /** Delegate whose findByCode fails with the queued exceptions, then answers empty. */
    static final class FailingDelegate implements LinkRepository {
        final List<RuntimeException> failures = new ArrayList<>();
        int calls;

        @Override
        public ShortLink insert(NewLink link) {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ShortLink> findByCode(ShortCode code) {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            return Optional.empty();
        }

        @Override
        public Optional<ShortLink> findByRequestIdentity(String requestIdentity) {
            return findByCode(null);
        }
    }

    private final FailingDelegate delegate = new FailingDelegate();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryingLinkRepository repository = new RetryingLinkRepository(delegate,
            new RetryingLinkRepository.Policy(3, Duration.ofSeconds(1), 0.2), sleeps::add, new Random(7));

    @Test
    void transientFailuresAreRetriedWithBackoffThenSucceed() {
        delegate.failures.add(new CannotGetJdbcConnectionException("pool exhausted"));
        delegate.failures.add(new QueryTimeoutException("slow"));

        assertThat(repository.findByCode(ShortCode.of("abcd"))).isEmpty();
        assertThat(delegate.calls).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(800), Duration.ofMillis(1200));
        assertThat(sleeps.get(1)).isBetween(Duration.ofMillis(1600), Duration.ofMillis(2400));
    }

    @Test
    void exhaustedRetriesRaiseStorageUnavailableAfterThreeRetries() {
        for (int i = 0; i < 5; i++) {
            delegate.failures.add(new CannotGetJdbcConnectionException("down"));
        }

        assertThatThrownBy(() -> repository.findByCode(ShortCode.of("abcd"))).isInstanceOf(StorageUnavailableException.class);
        assertThat(delegate.calls).as("1 attempt + 3 retries").isEqualTo(4);
        assertThat(sleeps).hasSize(3);
        assertThat(sleeps.get(2)).isBetween(Duration.ofMillis(3200), Duration.ofMillis(4800));
    }

    @Test
    void permanentFailuresAreNotRetried() {
        delegate.failures.add(new DataIntegrityViolationException("bad data"));

        assertThatThrownBy(() -> repository.findByCode(ShortCode.of("abcd"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void domainOutcomesPassThroughWithoutRetry() {
        delegate.failures.add(new DuplicateShortCodeException(ShortCode.of("abcd")));

        assertThatThrownBy(() -> repository.insert(null)).isInstanceOf(DuplicateShortCodeException.class);
        assertThat(delegate.calls).isEqualTo(1);
    }
}
