package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shortener.domain.LinkService;
import com.agenticengineering.urlshortener.shortener.domain.LinkStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/links/{code}/status (SCN-A; contracts/openapi.yaml). Read-only: no redirect and no redirect event, so
 * analytics keep counting redirect attempts only (FR-012).
 */
@RestController
public class LinkStatusController {

    private final LinkService links;

    public LinkStatusController(LinkService links) {
        this.links = links;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LinkStatusResponse(@JsonProperty("short_code") String shortCode, @JsonProperty("status") String status,
            @JsonProperty("expires_at") Instant expiresAt) {
    }

    @GetMapping(path = "/api/v1/links/{code}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkStatusResponse status(@PathVariable String code) {
        LinkStatus status = links.status(code);
        return new LinkStatusResponse(status.shortCode(), status.state().name(), status.expiresAt());
    }
}
