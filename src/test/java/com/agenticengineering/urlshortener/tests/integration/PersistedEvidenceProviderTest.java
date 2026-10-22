package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.PersistedEvidenceProvider;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The production N12 evidence source as wired by Spring: relative input paths resolve against the working directory, so
 * DEP-01 evaluates the real pom.xml and inventory and AUD-02 the run. Regression guard, not red-first: written after
 * T068 found that Spring resolves a relative Path against the classpath; the suspected defect did not occur for these
 * values (no classpath resource has their names), and the provider now takes Strings.
 * Requirements: FR-032, FR-033 (versioned policy evaluation on real evidence).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PersistedEvidenceProviderTest {

    @Autowired
    private PersistedEvidenceProvider provider;

    @Autowired
    private OrchestrationEngine engine;

    @Test
    void productionEvidenceReadsTheRealBuildFiles() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        PolicyInput input = provider.gather(runId);

        Evidence dep01 = input.evidence().get("DEP-01");
        assertThat(dep01.status()).as(dep01.ref()).isEqualTo(Evidence.Status.OK);
        assertThat(input.evidence().get("AUD-02").status()).isEqualTo(Evidence.Status.OK);
    }

    /**
     * Option (a), human decision 2026-10-22: every automated check has a source. In the test configuration the suite run
     * and the secret scan are switched off (they would run Maven and gitleaks against this workspace during its own
     * build), so those checks fail closed and say why.
     */
    @Test
    void everyCheckHasASourceAndTheSwitchedOffOnesFailClosed() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        PolicyInput input = provider.gather(runId);

        assertThat(input.evidence()).containsKeys("SEC-01", "SEC-02", "SEC-03", "PRV-01", "AUD-01", "AUD-02", "CMP-02", "DEP-01", "DEP-02",
                "LIC-01", "CHG-01", "CHG-02", "CHG-03");
        assertThat(input.evidence().get("SEC-01").ref()).contains("disabled");
        assertThat(input.evidence().get("SEC-03").ref()).contains("disabled");
        assertThat(input.evidence().get("LIC-01").status()).isEqualTo(Evidence.Status.OK);
        assertThat(input.evidence().get("DEP-02").status()).isEqualTo(Evidence.Status.DEGRADED);
        assertThat(input.evidence().get("CHG-01").status()).as("no design approval yet").isEqualTo(Evidence.Status.NOT_OK);
        assertThat(input.evidence().get("CHG-02").status()).as("no replan in the run").isEqualTo(Evidence.Status.NOT_APPLICABLE);
        assertThat(input.attestations()).containsKeys("PRV-02", "LIC-02", "DEP-02");
    }
}
