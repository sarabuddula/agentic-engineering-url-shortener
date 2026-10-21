package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateRequestIdentityException;
import com.agenticengineering.urlshortener.shortener.domain.DuplicateShortCodeException;
import com.agenticengineering.urlshortener.shortener.domain.LinkRepository;
import com.agenticengineering.urlshortener.shortener.domain.NewLink;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Test double for LinkRepository with the same uniqueness rules as the database (short code, request identity). */
public final class InMemoryLinkRepository implements LinkRepository {

    private final List<ShortLink> links = new ArrayList<>();

    @Override
    public synchronized ShortLink insert(NewLink link) {
        if (links.stream().anyMatch(l -> l.code().equals(link.code()))) {
            throw new DuplicateShortCodeException(link.code());
        }
        if (links.stream().anyMatch(l -> l.requestIdentity().equals(link.requestIdentity()))) {
            throw new DuplicateRequestIdentityException(link.requestIdentity());
        }
        ShortLink stored = new ShortLink(links.size() + 1L, link.code(), link.target(), link.requestIdentity(),
                link.payloadHash(), link.createdAt(), link.expiresAt());
        links.add(stored);
        return stored;
    }

    @Override
    public synchronized Optional<ShortLink> findByCode(ShortCode code) {
        return links.stream().filter(l -> l.code().equals(code)).findFirst();
    }

    @Override
    public synchronized Optional<ShortLink> findByRequestIdentity(String requestIdentity) {
        return links.stream().filter(l -> l.requestIdentity().equals(requestIdentity)).findFirst();
    }

    public synchronized int size() {
        return links.size();
    }
}
