package com.agenticengineering.urlshortener.orchestration.reliability;

/** Recovery mechanisms FP-1..FP-4 (plan section 6); {@link #code()} is the value stored in FAILURE_EVENT. */
public enum RecoveryMechanism {
    FP_1_RETRY("FP-1"),
    FP_2_FALLBACK("FP-2"),
    FP_3_COMPENSATION("FP-3"),
    FP_4_SAFE_STOP("FP-4");

    private final String code;

    RecoveryMechanism(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static RecoveryMechanism fromCode(String code) {
        for (RecoveryMechanism mechanism : values()) {
            if (mechanism.code.equals(code)) {
                return mechanism;
            }
        }
        throw new IllegalArgumentException("unknown recovery mechanism " + code);
    }
}
