package com.agenticengineering.urlshortener.shortener.api;

import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.value.InvalidShortCodeException;
import com.agenticengineering.urlshortener.shared.value.ShortCode;
import com.agenticengineering.urlshortener.shortener.analytics.RedirectAnalytics;
import com.agenticengineering.urlshortener.shortener.analytics.Summary;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/analytics/summary (T040; FR-012 reporting, FR-015 loss; public per FR-045). Without a code the summary
 * covers all attempts and includes the durably reported loss; with a code it counts that requested code only, and the
 * loss is omitted because a failed capture cannot be attributed to a code.
 */
@RestController
public class AnalyticsController {

    private final RedirectAnalytics analytics;

    public AnalyticsController(RedirectAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping(path = "/api/v1/analytics/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> summary(@RequestParam(name = "code", required = false) String code) {
        if (code != null) {
            try {
                ShortCode.of(code);
            } catch (InvalidShortCodeException e) {
                throw new InvalidRequestException("code must be 4-16 base-62 characters");
            }
        }
        Summary summary = analytics.summary(code);
        Map<String, Object> body = new LinkedHashMap<>();
        if (code != null) {
            body.put("code", code);
        }
        body.put("window", "all-time");
        body.put("redirected", summary.redirected());
        body.put("expired", summary.expired());
        body.put("not_found", summary.notFound());
        if (code == null) {
            body.put("unrecorded_capture_failures", summary.reportedLoss());
            if (summary.latestLossReportId() != null) {
                body.put("loss_report_ref", "loss-report-" + summary.latestLossReportId());
            }
        }
        return body;
    }
}
