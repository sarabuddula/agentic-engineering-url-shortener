package com.agenticengineering.urlshortener.tests.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyEvaluator;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyOutcome;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyResult;
import com.agenticengineering.urlshortener.orchestration.policy.PolicySet;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T057: versioned policy evaluation over the data-model inventory POL-2026-10-18-v1 (FR-032, FR-033, FR-039;
 * Constitution VI). Every check yields exactly one of PASS / FAIL / EXCEPTION_REQUESTED / NOT_APPLICABLE; missing
 * evidence fails closed; attestation checks need a recorded human attestation.
 */
class PolicyEvaluatorTest {

    private static final List<String> INVENTORY = List.of("SEC-01", "SEC-02", "SEC-03", "CMP-01", "CMP-02", "PRV-01", "PRV-02",
            "AUD-01", "AUD-02", "DEP-01", "DEP-02", "LIC-01", "LIC-02", "CHG-01", "CHG-02", "CHG-03");

    private final PolicyEvaluator evaluator = new PolicyEvaluator(PolicySet.v20261018());

    @Test
    void theInventoryIsTheDataModelsSixteenChecksAllMandatory() {
        PolicySet set = PolicySet.v20261018();

        assertThat(set.version()).isEqualTo("POL-2026-10-18-v1");
        assertThat(set.checks()).extracting(PolicySet.Check::id).containsExactlyElementsOf(INVENTORY);
        assertThat(set.checks()).allSatisfy(c -> assertThat(c.mandatory()).isTrue());
        assertThat(set.check("PRV-02").mode()).isEqualTo(PolicySet.Mode.ATTESTATION);
        assertThat(set.check("LIC-02").mode()).isEqualTo(PolicySet.Mode.ATTESTATION);
        assertThat(set.check("SEC-01").mode()).isEqualTo(PolicySet.Mode.AUTOMATED);
    }

    @Test
    void completeEvidenceGivesExactlyOnePassPerCheck() {
        PolicyResult result = evaluator.evaluate(allGood());

        assertThat(result.policyVersion()).isEqualTo("POL-2026-10-18-v1");
        assertThat(result.evaluations()).extracting(PolicyResult.Evaluation::checkId).containsExactlyElementsOf(INVENTORY);
        assertThat(result.evaluations()).allSatisfy(e -> assertThat(e.outcome()).isEqualTo(PolicyOutcome.PASS));
        assertThat(result.mandatoryFailures()).isEmpty();
    }

    @Test
    void missingEvidenceFailsClosed() {
        Map<String, Evidence> evidence = new HashMap<>(allGood().evidence());
        evidence.remove("SEC-02");

        PolicyResult result = evaluator.evaluate(new PolicyInput(evidence, allGood().attestations(), Map.of()));

        assertThat(result.outcome("SEC-02")).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.evaluation("SEC-02").reason()).contains("no evidence");
        assertThat(result.mandatoryFailures()).containsExactly("SEC-02");
    }

    @Test
    void anUndeclaredDependencyFailsDep01WithItsEvidenceReference() {
        Map<String, Evidence> evidence = new HashMap<>(allGood().evidence());
        evidence.put("DEP-01", Evidence.notOk("pom.xml: com.example:undeclared-lib not in docs/compliance/licenses.xml"));

        PolicyResult result = evaluator.evaluate(new PolicyInput(evidence, allGood().attestations(), Map.of()));

        assertThat(result.outcome("DEP-01")).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.evaluation("DEP-01").evidenceRef()).contains("undeclared-lib");
        assertThat(result.outcome("CMP-01")).as("still exactly one outcome per check").isEqualTo(PolicyOutcome.PASS);
    }

    @Test
    void attestationChecksNeedARecordedHumanAttestation() {
        PolicyResult result = evaluator.evaluate(new PolicyInput(allGood().evidence(), Map.of(), Map.of()));

        assertThat(result.outcome("PRV-02")).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.outcome("LIC-02")).isEqualTo(PolicyOutcome.FAIL);
        assertThat(result.evaluation("PRV-02").reason()).contains("attestation");
    }

    @Test
    void degradedDependencyScanIsEvaluatedAsAnAttestation() {
        Map<String, Evidence> evidence = new HashMap<>(allGood().evidence());
        evidence.put("DEP-02", Evidence.degraded("docs/compliance/dep-02-degradation.md"));

        PolicyResult withoutAttestation = evaluator.evaluate(new PolicyInput(evidence, allGood().attestations(), Map.of()));
        Map<String, PolicyInput.Attestation> attested = new HashMap<>(allGood().attestations());
        attested.put("DEP-02", new PolicyInput.Attestation("demo-approver", "approvals/dep-02-attestation.md"));
        PolicyResult withAttestation = evaluator.evaluate(new PolicyInput(evidence, attested, Map.of()));

        assertThat(withoutAttestation.outcome("DEP-02")).isEqualTo(PolicyOutcome.FAIL);
        assertThat(withAttestation.outcome("DEP-02")).isEqualTo(PolicyOutcome.PASS);
        assertThat(withAttestation.evaluation("DEP-02").evidenceRef()).contains("dep-02-degradation.md").contains("attestation");
    }

    @Test
    void aFailingCheckWithAnExceptionRecordIsExceptionRequested() {
        Map<String, Evidence> evidence = new HashMap<>(allGood().evidence());
        evidence.put("DEP-01", Evidence.notOk("undeclared-lib"));
        Map<String, PolicyInput.ExceptionRef> exceptions = Map.of("DEP-01", new PolicyInput.ExceptionRef(42L,
                Instant.parse("2026-10-30T00:00:00Z")));

        PolicyResult result = evaluator.evaluate(new PolicyInput(evidence, allGood().attestations(), exceptions));

        assertThat(result.outcome("DEP-01")).isEqualTo(PolicyOutcome.EXCEPTION_REQUESTED);
        assertThat(result.evaluation("DEP-01").exceptionId()).isEqualTo(42L);
        assertThat(result.mandatoryFailures()).isEmpty();
    }

    @Test
    void notApplicableEvidenceIsRecordedWithItsReason() {
        Map<String, Evidence> evidence = new HashMap<>(allGood().evidence());
        evidence.put("CHG-02", Evidence.notApplicable("no replan event in this run"));

        PolicyResult result = evaluator.evaluate(new PolicyInput(evidence, allGood().attestations(), Map.of()));

        assertThat(result.outcome("CHG-02")).isEqualTo(PolicyOutcome.NOT_APPLICABLE);
        assertThat(result.evaluation("CHG-02").reason()).contains("no replan event");
    }

    private static PolicyInput allGood() {
        Map<String, Evidence> evidence = new HashMap<>();
        for (String id : INVENTORY) {
            if (!id.equals("CMP-01") && !id.equals("PRV-02") && !id.equals("LIC-02")) {
                evidence.put(id, Evidence.ok("evidence:" + id));
            }
        }
        Map<String, PolicyInput.Attestation> attestations = Map.of(
                "PRV-02", new PolicyInput.Attestation("demo-approver", "approvals/prv-02.md"),
                "LIC-02", new PolicyInput.Attestation("demo-approver", "approvals/lic-02.md"));
        return new PolicyInput(evidence, attestations, Map.of());
    }
}
