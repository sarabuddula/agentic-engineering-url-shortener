package com.agenticengineering.urlshortener.shortener.analytics;

import java.time.Instant;

/**
 * One resolution attempt (REDIRECT_EVENT, FR-012): link (null for NOT_FOUND), the code as requested, time and outcome.
 * Deliberately no client address, user agent or other personal data.
 */
public record RedirectEvent(Long shortLinkId, String attemptedCode, Instant occurredAt, Outcome outcome) {

    /** Column length of ATTEMPTED_CODE; longer requested paths are cut to this length. */
    public static final int MAX_ATTEMPTED_CODE_LENGTH = 64;

    public enum Outcome { REDIRECTED, EXPIRED, NOT_FOUND }
}
