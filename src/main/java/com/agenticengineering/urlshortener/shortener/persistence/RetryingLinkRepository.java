package com.agenticengineering.urlshortener.shortener.persistence;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.NewLink;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import com.agenticengineering.urlshortener.shortener.domain.StorageUnavailableException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Persistence failure handling for the link repository (T102; FR-009, NFR-002, plan section 6). Transient failures
 * (connection or resource failure, query timeout, other TransientDataAccessException) are retried at most
 * {@code maxRetries} times with exponential backoff and jitter (A-013: 1 s, 2 s, 4 s, +/-20%), then reported as
 * StorageUnavailableException (503). Everything else is permanent and propagates at once; domain outcomes such as
 * duplicates are not failures. A retried insert is safe: if the first attempt committed, the unique constraints turn
 * the retry into a duplicate, which the idempotency service resolves as a replay.
 */
public class RetryingLinkRepository implements LinkRepository {

    /** Retry bounds; A-013 values by default (app.persistence-retry). */
    public record Policy(int maxRetries, Duration initialBackoff, double jitter) {
    }

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration);
    }

    private static final Logger LOG = LoggerFactory.getLogger(RetryingLinkRepository.class);

    private final LinkRepository delegate;
    private final Policy policy;
    private final Sleeper sleeper;
    private final Random random;

    public RetryingLinkRepository(LinkRepository delegate, Policy policy, Sleeper sleeper, Random random) {
        this.delegate = Objects.requireNonNull(delegate);
        this.policy = Objects.requireNonNull(policy);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public ShortLink insert(NewLink link) {
        return withRetry("insert", () -> delegate.insert(link));
    }

    @Override
    public Optional<ShortLink> findByCode(ShortCode code) {
        return withRetry("findByCode", () -> delegate.findByCode(code));
    }

    @Override
    public Optional<ShortLink> findByRequestIdentity(String requestIdentity) {
        return withRetry("findByRequestIdentity", () -> delegate.findByRequestIdentity(requestIdentity));
    }

    private <T> T withRetry(String operation, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            if (attempt > 0) {
                sleeper.sleep(backoff(attempt));
            }
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (!isTransient(e)) {
                    throw e;
                }
                last = e;
                LOG.warn("transient persistence failure operation={} attempt={} type={}", operation, attempt + 1,
                        e.getClass().getSimpleName());
            }
        }
        throw new StorageUnavailableException(policy.maxRetries() + 1, last);
    }

    private Duration backoff(int retry) {
        double factor = 1 + policy.jitter() * (2 * random.nextDouble() - 1);
        long base = policy.initialBackoff().toMillis() << (retry - 1);
        return Duration.ofMillis(Math.round(base * factor));
    }

    static boolean isTransient(RuntimeException e) {
        return e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException;
    }
}
