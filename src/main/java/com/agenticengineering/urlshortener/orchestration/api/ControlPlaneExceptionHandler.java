package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalExpiredException;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.engine.ResumeConflictException;
import com.agenticengineering.urlshortener.orchestration.replan.ReplanConflictException;
import com.agenticengineering.urlshortener.orchestration.stages.DesignRevisions;
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

    @ExceptionHandler(ResumeConflictException.class)
    ResponseEntity<Void> resumeConflict(ResumeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build(); // the contract's 409 for /resume defines no body
    }

    @ExceptionHandler(ReplanConflictException.class)
    ResponseEntity<Map<String, Object>> replanConflict(ReplanConflictException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "REPLAN_CONFLICT", "Replan not possible: " + e.getMessage(), request);
    }

    @ExceptionHandler(DesignRevisions.DesignNotFoundException.class)
    ResponseEntity<Map<String, Object>> designNotFound(DesignRevisions.DesignNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DESIGN_NOT_FOUND", "No design to revise: " + e.getMessage(), request);
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
