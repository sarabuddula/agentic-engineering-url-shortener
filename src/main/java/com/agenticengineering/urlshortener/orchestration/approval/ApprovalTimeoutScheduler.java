package com.agenticengineering.urlshortener.orchestration.approval;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives approval escalation and expiry (PVT-005) every 30 seconds; expiry safe-stops the waiting path. */
@Component
public class ApprovalTimeoutScheduler {

    private final OrchestrationEngine engine;

    public ApprovalTimeoutScheduler(OrchestrationEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${app.approval-timeout-check-interval:PT30S}")
    public void check() {
        engine.handleTimeouts();
    }
}
