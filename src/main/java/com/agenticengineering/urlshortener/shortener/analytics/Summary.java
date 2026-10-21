package com.agenticengineering.urlshortener.shortener.analytics;

/**
 * Outcome counts from REDIRECT_EVENT, optionally for one requested code, plus the durably reported loss (sum of
 * LOSS_REPORT counts) and the latest loss report id.
 */
public record Summary(String code, long redirected, long expired, long notFound, long reportedLoss, Long latestLossReportId) {
}
