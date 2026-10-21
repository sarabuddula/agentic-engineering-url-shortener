package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Idempotent creation (T034; FR-007, ADR-006). Same identity and same normalized payload replays the existing link
 * (200); same identity with a different payload is a conflict (409) and changes nothing; a new identity creates a link
 * (201). Concurrent requests with one identity race on the store's unique constraint; the loser re-reads and returns
 * the winner's link (or the conflict), so exactly one link exists.
 */
public class IdempotencyService {

    /** The link and whether this request created it (201) or replayed it (200). */
    public record Outcome(ShortLink link, boolean created) {
    }

    private final LinkService links;
    private final LinkRepository repository;

    public IdempotencyService(LinkService links, LinkRepository repository) {
        this.links = Objects.requireNonNull(links);
        this.repository = Objects.requireNonNull(repository);
    }

    public Outcome createOrReplay(String requestIdentity, TargetUrl target, Instant requestedExpiresAt) {
        String payloadHash = PayloadHash.of(target, requestedExpiresAt);
        Optional<Outcome> existing = replayOrConflict(requestIdentity, payloadHash);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return new Outcome(links.create(requestIdentity, target, requestedExpiresAt), true);
        } catch (DuplicateRequestIdentityException raceLost) {
            return replayOrConflict(requestIdentity, payloadHash)
                    .orElseThrow(() -> new IllegalStateException("request identity bound but not found"));
        }
    }

    private Optional<Outcome> replayOrConflict(String requestIdentity, String payloadHash) {
        Optional<ShortLink> bound = repository.findByRequestIdentity(requestIdentity);
        if (bound.isEmpty()) {
            return Optional.empty();
        }
        if (!bound.get().payloadHash().equals(payloadHash)) {
            throw new RequestIdentityConflictException();
        }
        return Optional.of(new Outcome(bound.get(), false));
    }
}
