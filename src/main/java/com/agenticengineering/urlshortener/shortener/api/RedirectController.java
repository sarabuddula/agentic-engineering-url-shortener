package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shortener.analytics.RedirectAnalytics;
import com.agenticengineering.urlshortener.shortener.analytics.RedirectEvent;
import com.agenticengineering.urlshortener.shortener.domain.LinkService;
import com.agenticengineering.urlshortener.shortener.domain.Resolution;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /{code} (T037; FR-005, FR-006, FR-014; 302 and 410 per the human decision of 2026-10-19). Expiry is derived at
 * this moment from expires_at (T038), so once a link is expired it never redirects again. The service never fetches
 * the target (D-003); only Redirect carries a Location header.
 */
@RestController
public class RedirectController {

    private final LinkService links;
    private final RedirectAnalytics analytics;

    public RedirectController(LinkService links, RedirectAnalytics analytics) {
        this.links = links;
        this.analytics = analytics;
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> resolve(@PathVariable String code, HttpServletRequest request) {
        Resolution resolution = links.resolve(code);
        record(resolution, code); // synchronous capture in the redirect path (FR-012); never throws (FR-015)
        return switch (resolution) {
            case Resolution.Redirect redirect -> ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirect.target().value())).build();
            case Resolution.Expired expired -> error(HttpStatus.GONE, "LINK_EXPIRED", "This short link has expired", request);
            case Resolution.NotFound notFound -> error(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", "No such short code", request);
        };
    }

    private void record(Resolution resolution, String code) {
        switch (resolution) {
            case Resolution.Redirect redirect -> analytics.record(redirect.link().id(), code, RedirectEvent.Outcome.REDIRECTED);
            case Resolution.Expired expired -> analytics.record(expired.link().id(), code, RedirectEvent.Outcome.EXPIRED);
            case Resolution.NotFound notFound -> analytics.record(null, code, RedirectEvent.Outcome.NOT_FOUND);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, RequestIds.of(request)).toBody());
    }
}
