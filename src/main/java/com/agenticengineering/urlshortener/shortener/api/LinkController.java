package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.value.TargetUrl;
import com.agenticengineering.urlshortener.shortener.domain.IdempotencyService;
import com.agenticengineering.urlshortener.shortener.domain.ShortLink;
import com.agenticengineering.urlshortener.shortener.domain.UrlValidator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/links (T036; FR-001, FR-002, FR-007, FR-011; contracts/openapi.yaml). Thin adapter: every check runs
 * before creation, so no short code is allocated on an error path. 201 new link, 200 idempotent replay.
 */
@RestController
public class LinkController {

    private final UrlValidator validator;
    private final IdempotencyService creation;

    public LinkController(UrlValidator validator, IdempotencyService creation) {
        this.validator = validator;
        this.creation = creation;
    }

    public record CreateLinkRequest(@JsonProperty("target_url") String targetUrl, @JsonProperty("expires_at") String expiresAt) {
    }

    public record ShortLinkResponse(@JsonProperty("short_code") String shortCode, @JsonProperty("target_url") String targetUrl,
            @JsonProperty("expires_at") Instant expiresAt, @JsonProperty("created_at") Instant createdAt) {

        static ShortLinkResponse of(ShortLink link) {
            return new ShortLinkResponse(link.code().value(), link.target().value(), link.expiresAt(), link.createdAt());
        }
    }

    @PostMapping(path = "/api/v1/links", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ShortLinkResponse> create(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateLinkRequest request) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 64) {
            throw new InvalidRequestException("The Idempotency-Key header is required (8-64 characters)");
        }
        TargetUrl target = validator.validate(request.targetUrl());
        Instant expiresAt = parseExpiry(request.expiresAt());
        IdempotencyService.Outcome outcome = creation.createOrReplay(idempotencyKey, target, expiresAt);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ShortLinkResponse.of(outcome.link()));
    }

    private static Instant parseExpiry(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("expires_at must be an ISO-8601 date-time");
        }
    }
}
