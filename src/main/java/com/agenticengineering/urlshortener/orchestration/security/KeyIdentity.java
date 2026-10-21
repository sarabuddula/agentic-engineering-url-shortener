package com.agenticengineering.urlshortener.orchestration.security;

import java.util.Arrays;
import java.util.Objects;

/**
 * An authenticated key: ID, role and label only. The hash stays inside {@link KeyIdentityStore}, so audit records and
 * logs that carry this object can only show the key ID (D-004).
 */
public record KeyIdentity(String keyId, Role role, String label) {

    public enum Role { ENGINEER, APPROVER, RELEASE_OWNER, REVIEWER }

    public KeyIdentity {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(role, "role");
    }

    public boolean hasAnyRole(Role... roles) {
        return Arrays.asList(roles).contains(role);
    }
}
