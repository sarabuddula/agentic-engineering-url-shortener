package com.agenticengineering.urlshortener.orchestration.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Control-plane settings under {@code app} (quickstart configuration table; plan sections 3, 5 and 8). */
@ConfigurationProperties("app")
public record OrchestrationProperties(
        int approvalTimeoutMinutes,
        int reworkCap,
        int replanCap,
        int syncWaitApprovalWindows,
        RateLimit rateLimit) {

    /** {@code app.rate-limit}: per-key control-plane limit (A-008c) and failed-auth limit with block (A-008d, D-004). */
    public record RateLimit(ControlPlane controlPlanePerKey, FailedAuth failedAuthPerIp) {
    }

    public record ControlPlane(int requestsPerMinute) {
    }

    public record FailedAuth(int failuresPerMinute, Duration blockDuration) {
    }
}
