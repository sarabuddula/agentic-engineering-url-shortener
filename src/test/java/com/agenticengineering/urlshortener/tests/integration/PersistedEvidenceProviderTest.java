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
}
