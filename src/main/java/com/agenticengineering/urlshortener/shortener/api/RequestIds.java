package com.agenticengineering.urlshortener.shortener.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** The request's correlation id (set by RequestCorrelationFilter, T022), or a fresh one outside a filtered request. */
final class RequestIds {

    private RequestIds() {
    }

    static String of(HttpServletRequest request) {
        Object id = request.getAttribute(GlobalExceptionHandler.REQUEST_ID_ATTRIBUTE);
        return id instanceof String s && !s.isBlank() ? s : UUID.randomUUID().toString();
    }
}
