package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.policy.AttestationReader;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.LicenseCheck;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.policy.SecretScan;
import com.agenticengineering.urlshortener.orchestration.policy.SuiteEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Evidence sources for the remaining N12 checks (option (a), human decision 2026-10-22): signed attestation forms
 * (PRV-02, LIC-02, DEP-02), the license inventory against approved licenses (LIC-01), the security and compliance test
 * suites the run executes (SEC-01, SEC-02, PRV-01, AUD-01, CMP-02) and a gitleaks secret scan (SEC-03). Every source
 * fails closed.
 * Requirements: FR-032, FR-033 (policy evidence sources).
 */
class PolicyEvidenceSourcesTest {

    @TempDir
    Path dir;

    @Test
    void signedAttestationFormsAreRead() throws IOException {
        Map<String, PolicyInput.Attestation> attestations = AttestationReader.read(Path.of("docs/attestations"));

        assertThat(attestations).containsKeys("PRV-02", "LIC-02", "DEP-02");
        assertThat(attestations.get("DEP-02").attestedByKeyId()).isEqualTo("demo-release-owner");
        assertThat(attestations.get("DEP-02").ref()).contains("docs/attestations/DEP-02.md").contains("b28f515");
    }

    @Test
    void theLatestDecisionCountsAndNotAttestedIsNoAttestation() throws IOException {
        Files.writeString(dir.resolve("LIC-02.md"), """
                ATTESTED / NOT ATTESTED: ATTESTED
                Attested by (key ID, never a raw key): demo-release-owner
                Reviewed commit: aaaaaaa
                ## Re-review
                ATTESTED / NOT ATTESTED: NOT ATTESTED
                Attested by (key ID, never a raw key): demo-release-owner
                Reviewed commit: bbbbbbb
                """);
        Files.writeString(dir.resolve("PRV-02.md"), "ATTESTED / NOT ATTESTED: ____\nAttested by (key ID, never a raw key): ____\n");

        assertThat(AttestationReader.read(dir)).isEmpty();
    }

    @Test
    void everyInventoryLicenseMustBeApproved() throws IOException {
        assertThat(LicenseCheck.evaluate(Path.of("docs/compliance/licenses.xml")).status()).isEqualTo(Evidence.Status.OK);

        Path inventory = dir.resolve("licenses.xml");
        Files.writeString(inventory, Files.readString(Path.of("docs/compliance/licenses.xml"))
                .replaceFirst("<name>MIT</name>", "<name>Business Source License 1.1</name>"));
        Evidence unapproved = LicenseCheck.evaluate(inventory);
        assertThat(unapproved.status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(unapproved.ref()).contains("Business Source License 1.1");
        assertThat(LicenseCheck.evaluate(dir.resolve("absent.xml")).status()).isEqualTo(Evidence.Status.NOT_OK);
    }

    @Test
    void suiteEvidenceNeedsEveryMappedClassToHavePassed() {
        Map<String, List<String>> suites = Map.of("SEC-01", List.of("a.UrlValidationTest"), "PRV-01", List.of("a.FieldsTest", "a.CaptureTest"));
        Map<String, String> results = Map.of("a.UrlValidationTest", "PASSED", "a.FieldsTest", "PASSED", "a.CaptureTest", "FAILED");

        Map<String, Evidence> evidence = SuiteEvidence.evaluate(suites, results, "operation:tr-1");

        assertThat(evidence.get("SEC-01").status()).isEqualTo(Evidence.Status.OK);
        assertThat(evidence.get("PRV-01").status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(evidence.get("PRV-01").ref()).contains("a.CaptureTest FAILED");
        assertThat(SuiteEvidence.evaluate(suites, Map.of(), "operation:tr-2").get("SEC-01").ref()).contains("not executed");
    }

    @Test
    void theSecretScanPassesACleanTreeAndFailsClosedWithoutTheTool() throws IOException {
        Files.writeString(dir.resolve("README.md"), "nothing secret here\n");

        assertThat(SecretScan.run(dir, "gitleaks").status()).isEqualTo(Evidence.Status.OK);
        Evidence missing = SecretScan.run(dir, "no-such-gitleaks");
        assertThat(missing.status()).isEqualTo(Evidence.Status.NOT_OK);
        assertThat(missing.ref()).contains("unavailable");
    }
}
