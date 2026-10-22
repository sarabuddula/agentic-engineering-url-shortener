package com.agenticengineering.urlshortener.orchestration.policy;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Production evidence for N12, limited to what the system can verify today: AUD-02 from the run's chain verification.
 * Every other check has no evidence yet and therefore fails closed, so a production run is honestly NOT READY until the
 * evidence sources (T094, T107) exist.
 */
@Component
public class PersistedEvidenceProvider implements PolicyEvidenceProvider {

    private final AuditChain chain;

    public PersistedEvidenceProvider(AuditChain chain) {
        this.chain = chain;
    }

    @Override
    public PolicyInput gather(String runId) {
        Map<String, Evidence> evidence = new HashMap<>();
        ChainVerification verification = chain.verify(runId);
        evidence.put("AUD-02", verification.intact()
                ? Evidence.ok("chain intact: " + verification.eventCount() + " events")
                : Evidence.notOk("chain broken at " + verification.failedPosition() + " (" + verification.check() + ")"));
        return new PolicyInput(evidence, Map.of(), Map.of());
    }
}
