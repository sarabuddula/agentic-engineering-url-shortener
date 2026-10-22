package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.InvalidTargetUrlException;
import com.agenticengineering.urlshortener.shortener.domain.InvalidExpirationException;
import com.agenticengineering.urlshortener.shortener.domain.RequestIdentityConflictException;
import com.agenticengineering.urlshortener.shortener.domain.StorageUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the contract Error schema (T017; placed in shortener/api by human decision 2026-10-21 because
 * shared stays framework-free). Messages never echo request input or internal exception text (NFR-001).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Request attribute holding the correlation id; set by the request-correlation filter (T022). */
    public static final String REQUEST_ID_ATTRIBUTE = "app.requestId";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTargetUrlException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidTargetUrlException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, error("URL_REJECTED", urlMessage(e.reason()), request));
    }

    /** A malformed code cannot name an existing link, so it is reported as not found. */
    @ExceptionHandler(InvalidShortCodeException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidShortCodeException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, error("LINK_NOT_FOUND", "No such short code", request));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidRequestException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, error("INVALID_REQUEST", e.getMessage(), request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, error("INVALID_REQUEST", "The request body is not valid JSON", request));
    }

    @ExceptionHandler(InvalidExpirationException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidExpirationException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, error("INVALID_EXPIRATION", "expires_at must be in the future", request));
    }

    @ExceptionHandler(RequestIdentityConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(RequestIdentityConflictException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT,
                error("REQUEST_IDENTITY_CONFLICT", "Idempotency key already bound to a different payload", request));
    }

    /** Storage still failing after the bounded retries: the defined 503 (FR-009, contract ServiceUnavailable). */
    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handle(StorageUnavailableException e, HttpServletRequest request) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE,
                error("SERVICE_UNAVAILABLE", "The service is temporarily unavailable; retry later", request));
    }

    /**
     * Spring MVC's own errors (unknown route, wrong method, unsupported media type, missing header) keep their status;
     * all listed types implement org.springframework.web.ErrorResponse. Before T036 the catch-all below turned them into 500.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeException.class, ServletRequestBindingException.class, ErrorResponseException.class})
    public ResponseEntity<Map<String, Object>> handleFramework(Exception e, HttpServletRequest request) {
        HttpStatusCode status = ((org.springframework.web.ErrorResponse) e).getStatusCode();
        String code = switch (status.value()) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "INVALID_REQUEST" : "INTERNAL_ERROR";
        };
        String message = status.is4xxClientError() ? "The request cannot be served as sent" : "An internal error occurred";
        return ResponseEntity.status(status).body(error(code, message, request).toBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        String requestId = requestId(request);
        // Exception type and correlation id only: exception messages can carry connection strings or input (NFR-001).
        LOG.error("unexpected failure type={} request_id={}", e.getClass().getName(), requestId);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorResponse.of("INTERNAL_ERROR", "An internal error occurred", requestId));
    }

    /** Exhaustive switch: a new rejection reason cannot compile without a message. Messages never echo the input. */
    private static String urlMessage(InvalidTargetUrlException.Reason reason) {
        return switch (reason) {
            case BLANK -> "A target URL is required";
            case TOO_LONG -> "The target URL exceeds the accepted size";
            case MALFORMED -> "The target URL is not a valid absolute URL";
            case SCHEME_NOT_ALLOWED -> "Only HTTP and HTTPS targets are accepted";
            case MISSING_HOST -> "The target URL has no host";
            case USERINFO_NOT_ALLOWED -> "Target URLs must not contain user information";
            case LOCALHOST_NAME, INTERNAL_ADDRESS, IPV4_MAPPED_IPV6, NUMERIC_HOST_FORM -> "The target address is not allowed";
            case UNRESOLVABLE_HOST -> "The target host could not be resolved";
        };
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, ErrorResponse error) {
        return ResponseEntity.status(status).body(error.toBody());
    }

    private static ErrorResponse error(String code, String message, HttpServletRequest request) {
        return ErrorResponse.of(code, message, requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        return RequestIds.of(request);
    }
}
