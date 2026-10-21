package com.agenticengineering.urlshortener.tests.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recorded fallback for {@link OpenApiContractHarness} (plan technology decision 8): hand-written schema assertions for
 * the contract schemas in use. Kept in step with {@code contracts/openapi.yaml}; extended as endpoints are implemented.
 */
public final class ContractFallbackAssertions {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HEALTH_STATUS = Set.of("UP", "DOWN");
    private static final Set<String> COMPONENT_STATUS = Set.of("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN");

    private ContractFallbackAssertions() {
    }

    /** Schema HealthStatus: required status in {UP, DOWN}; optional components, each an object with a valid status. */
    public static List<String> healthStatusViolations(String json) {
        List<String> violations = new ArrayList<>();
        JsonNode root = parse(json, violations);
        if (root == null) {
            return violations;
        }
        JsonNode status = root.get("status");
        if (status == null || !status.isTextual()) {
            violations.add("status is required and must be a string");
        } else if (!HEALTH_STATUS.contains(status.asText())) {
            violations.add("status '" + status.asText() + "' is not one of " + HEALTH_STATUS);
        }
        JsonNode components = root.get("components");
        if (components != null) {
            if (!components.isObject()) {
                violations.add("components must be an object");
            } else {
                for (Map.Entry<String, JsonNode> component : components.properties()) {
                    JsonNode componentStatus = component.getValue().get("status");
                    if (componentStatus == null || !COMPONENT_STATUS.contains(componentStatus.asText())) {
                        violations.add("components." + component.getKey() + ".status is missing or not one of " + COMPONENT_STATUS);
                    }
                }
            }
        }
        return violations;
    }

    private static JsonNode parse(String json, List<String> violations) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                violations.add("body must be a JSON object");
                return null;
            }
            return node;
        } catch (JsonProcessingException e) {
            violations.add("body is not valid JSON: " + e.getOriginalMessage());
            return null;
        }
    }
}
