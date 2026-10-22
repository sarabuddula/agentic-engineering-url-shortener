package com.agenticengineering.urlshortener.orchestration.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suite-based checks (SEC-01, SEC-02, PRV-01, AUD-01, CMP-02): a check passes only when every test class mapped to it
 * ran in this run and passed; a failed class or one that did not run fails the check.
 */
public final class SuiteEvidence {

    private SuiteEvidence() {
    }

    /** {@code results}: test class (fully qualified) to its worst status in the run (PASSED, FAILED, SKIPPED). */
    public static Map<String, Evidence> evaluate(Map<String, List<String>> suites, Map<String, String> results, String operationRef) {
        Map<String, Evidence> evidence = new LinkedHashMap<>();
        suites.forEach((check, classes) -> {
            List<String> problems = new ArrayList<>();
            for (String testClass : classes) {
                String status = results.get(testClass);
                if (status == null) {
                    problems.add(testClass + " not executed");
                } else if (!status.equals("PASSED")) {
                    problems.add(testClass + " " + status);
                }
            }
            evidence.put(check, problems.isEmpty()
                    ? Evidence.ok(operationRef + ": " + String.join(", ", classes) + " passed")
                    : Evidence.notOk(operationRef + ": " + String.join("; ", problems)));
        });
        return evidence;
    }
}
