package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalExpiredException;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.api.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Control-plane exception mapping (contracts/openapi.yaml approvals). Ordered before the application plane's
 * catch-all handler, which cannot know these types (ADR-004 plane rule).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ControlPlaneExceptionHandler {

    @ExceptionHandler(SelfApprovalDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(SelfApprovalDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "SELF_APPROVAL_DENIED", "Approver key identity must differ from the requester's"
                + " and must be a human key", request);
    }

    @ExceptionHandler(ApprovalExpiredException.class)
    public ResponseEntity<Map<String, Object>> handle(ApprovalExpiredException e, HttpServletRequest request) {
        return respond(HttpStatus.GONE, "APPROVAL_EXPIRED", "The approval window elapsed through escalation; the path was"
                + " safe-stopped", request);
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        Object id = request.getAttribute(RequestAttributes.REQUEST_ID);
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message,
                id instanceof String s ? s : UUID.randomUUID().toString()).toBody());
    }
}
