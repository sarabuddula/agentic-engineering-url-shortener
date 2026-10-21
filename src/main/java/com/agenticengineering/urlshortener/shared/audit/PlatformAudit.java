package com.agenticengineering.urlshortener.shared.audit;

/**
 * Port for the platform audit stream: events outside any workflow run, such as rate-limit rejections and
 * authentication failures (FR-045, FR-047, plan section 4). Implemented by the control plane's hash-chained platform
 * audit; the application plane depends on this interface only (ADR-004 plane rules).
 */
public interface PlatformAudit {

    /** Appends one SYSTEM event; never carries key material or personal data. */
    void record(String action, String affected, String result, String reason);
}
