package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.reliability.DemoMetrics;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/metrics/demo (T083; FR-031, FR-045): REVIEWER key; always labelled DEMONSTRATION (C-004). */
@RestController
public class DemoMetricsController {

    private final DemoMetrics metrics;
    private final Roles roles;

    public DemoMetricsController(DemoMetrics metrics, Roles roles) {
        this.metrics = metrics;
        this.roles = roles;
    }

    @GetMapping(path = "/api/v1/metrics/demo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> demo(HttpServletRequest http) {
        roles.require(http, KeyIdentity.Role.REVIEWER);
        return metrics.snapshot();
    }
}
