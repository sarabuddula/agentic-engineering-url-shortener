package com.agenticengineering.urlshortener.orchestration.security;

import com.agenticengineering.urlshortener.shared.api.ErrorResponse;
import com.agenticengineering.urlshortener.shared.api.RequestAttributes;
import com.agenticengineering.urlshortener.shared.audit.PlatformAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Role-scoped key authentication for the control plane (T058; FR-045, ADR-008). Every /api/v1/orchestrator request
 * needs a valid X-API-Key; otherwise 401 UNAUTHORIZED, audited on the platform chain as AUTH_FAILED without any key
 * material. On success only the key ID and role are put on the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String CONTROL_PLANE_PREFIX = "/api/v1/orchestrator/";

    private final KeyIdentityStore keys;
    private final PlatformAudit audit;
    private final ObjectMapper json = new ObjectMapper();

    public ApiKeyAuthenticationFilter(KeyIdentityStore keys, PlatformAudit audit) {
        this.keys = keys;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CONTROL_PLANE_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader("X-API-Key");
        Optional<KeyIdentity> identity = keys.authenticate(presented);
        if (identity.isEmpty()) {
            String route = request.getMethod() + " " + request.getRequestURI();
            audit.record("AUTH_FAILED", route, "REJECTED", presented == null || presented.isEmpty() ? "missing key" : "invalid key");
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Object id = request.getAttribute(RequestAttributes.REQUEST_ID);
            json.writeValue(response.getOutputStream(), ErrorResponse.of("UNAUTHORIZED", "A valid X-API-Key is required",
                    id instanceof String s ? s : UUID.randomUUID().toString()).toBody());
            return;
        }
        request.setAttribute(RequestAttributes.KEY_ID, identity.get().keyId());
        request.setAttribute(RequestAttributes.KEY_ROLE, identity.get().role());
        chain.doFilter(request, response);
    }
}
