package com.agenticengineering.urlshortener.shared.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Contract Error schema (contracts/openapi.yaml): stable code, message, request_id, and retry_after_seconds only on 429
 * (FR-047). Framework-free (NFR-004); {@link #toBody()} gives the exact JSON field names, so no global serializer
 * setting is needed and fields that other schemas require as null are unaffected.
 */
public record ErrorResponse(String code, String message, String requestId, Integer retryAfterSeconds) {

    public ErrorResponse {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(requestId, "requestId");
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(code, message, requestId, null);
    }

    public static ErrorResponse rateLimited(String message, String requestId, int retryAfterSeconds) {
        return new ErrorResponse("RATE_LIMITED", message, requestId, retryAfterSeconds);
    }

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("request_id", requestId);
        if (retryAfterSeconds != null) {
            body.put("retry_after_seconds", retryAfterSeconds);
        }
        return body;
    }
}
