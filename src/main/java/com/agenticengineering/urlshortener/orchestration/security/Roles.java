package com.agenticengineering.urlshortener.orchestration.security;

import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.shared.api.RequestAttributes;
import com.agenticengineering.urlshortener.shared.audit.PlatformAudit;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** Endpoint role checks (contract x-required-roles, FR-045): a missing role is audited as ACCESS_DENIED, then 403. */
@Component
public class Roles {

    private final PlatformAudit audit;

    public Roles(PlatformAudit audit) {
        this.audit = audit;
    }

    /** @return the caller's key ID when its role is one of {@code allowed} */
    public String require(HttpServletRequest request, KeyIdentity.Role... allowed) {
        String keyId = (String) request.getAttribute(RequestAttributes.KEY_ID);
        KeyIdentity.Role role = (KeyIdentity.Role) request.getAttribute(RequestAttributes.KEY_ROLE);
        if (keyId == null || role == null || !Arrays.asList(allowed).contains(role)) {
            audit.record("ACCESS_DENIED", request.getMethod() + " " + request.getRequestURI(), "REJECTED",
                    "key " + keyId + " with role " + role + " lacks one of " + Arrays.toString(allowed));
            throw new ForbiddenException("role required: " + Arrays.toString(allowed));
        }
        return keyId;
    }
}
