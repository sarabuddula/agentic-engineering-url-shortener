package com.agenticengineering.urlshortener.orchestration.approval;

import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;

/**
 * Approval kinds with their plan section 5 gate and the role ADR-008 requires: G1-G4 APPROVER, G5-G8 RELEASE_OWNER.
 * REPLAN_ACCEPTANCE and CHANGE_APPROVAL have no gate number; APPROVAL.KIND stores every kind and GATE is null for these
 * two (fifth governed change, 2026-10-21, T066).
 */
public enum ApprovalKind {
    CLARIFICATION("G1", KeyIdentity.Role.APPROVER),
    DESIGN_APPROVAL("G2", KeyIdentity.Role.APPROVER),
    SECURITY_SENSITIVE("G3", KeyIdentity.Role.APPROVER),
    DESTRUCTIVE_ACTION("G4", KeyIdentity.Role.APPROVER),
    POLICY_EXCEPTION("G5", KeyIdentity.Role.RELEASE_OWNER),
    ACCEPTED_LIMITATION("G6", KeyIdentity.Role.RELEASE_OWNER),
    RELEASE_AUTHORIZATION("G7", KeyIdentity.Role.RELEASE_OWNER),
    FINAL_SUBMISSION("G8", KeyIdentity.Role.RELEASE_OWNER),
    REPLAN_ACCEPTANCE(null, KeyIdentity.Role.APPROVER),
    CHANGE_APPROVAL(null, KeyIdentity.Role.APPROVER);

    private final String gate;
    private final KeyIdentity.Role requiredRole;

    ApprovalKind(String gate, KeyIdentity.Role requiredRole) {
        this.gate = gate;
        this.requiredRole = requiredRole;
    }

    /** The plan section 5 gate, or null for kinds without one. */
    public String gate() {
        return gate;
    }

    /** The gate where there is one, else the kind's name: what audit events name as the affected artifact. */
    public String label() {
        return gate != null ? gate : name();
    }

    public KeyIdentity.Role requiredRole() {
        return requiredRole;
    }

    public static ApprovalKind ofGate(String gate) {
        for (ApprovalKind kind : values()) {
            if (gate.equals(kind.gate)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown gate " + gate);
    }
}
