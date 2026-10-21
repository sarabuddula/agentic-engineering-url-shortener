package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the contract Error schema (T017; placed in shortener/api by human decision 2026-10-21 because
 * shared stays framework-free). Messages never echo request input or internal exception text (NFR-001).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Request attribute holding the correlation id; set by the request-correlation filter (T022). */
    public static final String REQUEST_ID_ATTRIBUTE = "app.requestId";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<InvalidTargetUrlException.Reason, String> URL_MESSAGES = Map.of(
            InvalidTargetUrlException.Reason.BLANK, "A target URL is required",
            InvalidTargetUrlException.Reason.TOO_LONG, "The target URL exceeds the accepted size",
            InvalidTargetUrlException.Reason.MALFORMED, "The target URL is not a valid absolute URL",
            InvalidTargetUrlException.Reason.SCHEME_NOT_ALLOWED, "Only HTTP and HTTPS targets are accepted",
            InvalidTargetUrlException.Reason.MISSING_HOST, "The target URL has no host");

    @ExceptionHandler(InvalidTargetUrlException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidTargetUrlException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ErrorResponse.of("URL_REJECTED", URL_MESSAGES.get(e.reason()), requestId(request)));
    }

    /** A malformed code cannot name an existing link, so it is reported as not found. */
    @ExceptionHandler(InvalidShortCodeException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidShortCodeException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ErrorResponse.of("LINK_NOT_FOUND", "No such short code", requestId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        String requestId = requestId(request);
        // Exception type and correlation id only: exception messages can carry connection strings or input (NFR-001).
        LOG.error("unexpected failure type={} request_id={}", e.getClass().getName(), requestId);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of("INTERNAL_ERROR", "An internal error occurred", requestId));
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, ErrorResponse error) {
        return ResponseEntity.status(status).body(error.toBody());
    }

    private static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return id instanceof String s && !s.isBlank() ? s : UUID.randomUUID().toString();
    }
}
