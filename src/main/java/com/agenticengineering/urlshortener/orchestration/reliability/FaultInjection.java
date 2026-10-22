package com.agenticengineering.urlshortener.orchestration.reliability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Demonstration fault injection for PVT-006 (T078): when app.fault-injection.halt-after-effect names an operation type,
 * the JVM halts immediately after such an effect is written and before its completion is recorded - the
 * after-effect/before-completion-record case. Off by default (empty); set only by the kill-and-restart test.
 */
@Component
public class FaultInjection {

    private static final Logger LOG = LoggerFactory.getLogger(FaultInjection.class);

    private final String haltAfter;

    public FaultInjection(@Value("${app.fault-injection.halt-after-effect:}") String haltAfter) {
        this.haltAfter = haltAfter;
        if (!haltAfter.isBlank()) {
            LOG.warn("FAULT INJECTION ACTIVE: the JVM halts after the first {} effect (demonstration only)", haltAfter);
        }
    }

    public void afterEffect(String operationType) {
        if (operationType.equals(haltAfter)) {
            LOG.warn("FAULT INJECTION: halting after a {} effect, before its completion record", operationType);
            Runtime.getRuntime().halt(137);
        }
    }
}
