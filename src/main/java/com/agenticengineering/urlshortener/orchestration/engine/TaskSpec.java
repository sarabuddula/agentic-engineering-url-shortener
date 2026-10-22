package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.List;

/** One decomposed task (N05 output): key, title, requirement references and the keys it depends on (FR-036). */
public record TaskSpec(String key, String title, String requirementRefs, List<String> dependsOn) {

    public TaskSpec {
        if (key == null || key.isBlank() || key.contains(":")) {
            throw new IllegalArgumentException("task key must be non-blank and contain no ':'");
        }
        dependsOn = List.copyOf(dependsOn);
    }
}
