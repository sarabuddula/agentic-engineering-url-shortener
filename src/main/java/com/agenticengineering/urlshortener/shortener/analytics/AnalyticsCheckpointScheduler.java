package com.agenticengineering.urlshortener.shortener.analytics;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the FR-015 loss checkpoint on a configurable interval and once more at shutdown. */
@Component
public class AnalyticsCheckpointScheduler {

    private final RedirectAnalytics analytics;

    public AnalyticsCheckpointScheduler(RedirectAnalytics analytics) {
        this.analytics = analytics;
    }

    @Scheduled(fixedDelayString = "${app.analytics.checkpoint-interval:PT60S}")
    public void checkpoint() {
        analytics.checkpoint();
    }

    @PreDestroy
    public void checkpointOnShutdown() {
        analytics.checkpoint();
    }
}
