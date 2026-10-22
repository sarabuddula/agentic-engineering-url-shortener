package com.agenticengineering.urlshortener.orchestration.policy;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production evidence for N12, limited to what the system can verify today: AUD-02 from the run's chain verification
 * and DEP-01 from the build file against the license inventory (paths relative to the working directory).
 * Every other check has no evidence yet and therefore fails closed, so a production run is honestly NOT READY until the
 * evidence sources (T094, T107) exist.
 */
@Component
public class PersistedEvidenceProvider implements PolicyEvidenceProvider {

    private final AuditChain chain;
    private final Path pom;
    private final Path inventory;

    public PersistedEvidenceProvider(AuditChain chain, @Value("${app.policy.pom-file:pom.xml}") Path pom,
            @Value("${app.policy.inventory-file:docs/compliance/licenses.xml}") Path inventory) {
        this.chain = chain;
        this.pom = pom;
        this.inventory = inventory;
    }

    @Override
    public PolicyInput gather(String runId) {
        Map<String, Evidence> evidence = new HashMap<>();
        ChainVerification verification = chain.verify(runId);
        evidence.put("AUD-02", verification.intact()
                ? Evidence.ok("chain intact: " + verification.eventCount() + " events")
                : Evidence.notOk("chain broken at " + verification.failedPosition() + " (" + verification.check() + ")"));
        evidence.put("DEP-01", DependencyInventoryCheck.evaluate(pom, inventory));
        return new PolicyInput(evidence, Map.of(), Map.of());
    }
}
