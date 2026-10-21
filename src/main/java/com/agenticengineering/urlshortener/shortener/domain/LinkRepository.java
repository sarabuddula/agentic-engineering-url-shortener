package com.agenticengineering.urlshortener.shortener.domain;

import com.agenticengineering.urlshortener.shared.value.ShortCode;
import java.util.Optional;

/**
 * Persistence port for short links (ADR-003). Uniqueness of short code and request identity is enforced by the store
 * (database constraints), reported through the two duplicate exceptions. There is no update or delete: codes are never
 * reassigned (FR-004).
 */
public interface LinkRepository {

    /**
     * @throws DuplicateShortCodeException when the code is already taken
     * @throws DuplicateRequestIdentityException when the request identity is already bound
     */
    ShortLink insert(NewLink link);

    Optional<ShortLink> findByCode(ShortCode code);

    Optional<ShortLink> findByRequestIdentity(String requestIdentity);
}
