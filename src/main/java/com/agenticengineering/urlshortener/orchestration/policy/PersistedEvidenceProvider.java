package com.agenticengineering.urlshortener.orchestration.policy;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.stages.TestExecutionStageExecutor;
import com.agenticengineering.urlshortener.orchestration.traceability.GitCommitLookup;
import com.agenticengineering.urlshortener.orchestration.traceability.ManifestChecker;
import com.agenticengineering.urlshortener.orchestration.traceability.MappingManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Production evidence for N12 (option (a), human decision 2026-10-22; data-model policy inventory). Every automated check
 * has a source, and every source fails closed:
 * <ul>
 * <li>SEC-01, SEC-02, PRV-01, AUD-01, CMP-02: the test classes verifying each check, run once for the run (a real Maven
 * subprocess through the N09 executor; outcomes recorded as the run's TEST_OUTCOME rows);</li>
 * <li>SEC-03: gitleaks over the workspace git history;</li>
 * <li>AUD-02: the run's chain verification; DEP-01: build file against the inventory; LIC-01: approved licenses;</li>
 * <li>DEP-02: a clean DependencyCheck report when one exists, otherwise DEGRADED (attestation required);</li>
 * <li>CHG-01: the run's design approval (and change approval when an approved requirement changes); CHG-02: re-validation
 * records of the run's replans; CHG-03: the mapping manifest checker, clean both ways;</li>
 * <li>PRV-02, LIC-02, DEP-02 (degraded): the signed attestation forms.</li>
 * </ul>
 * Paths are relative to the working directory (app.workspace-root); they are taken as Strings because Spring's Path
 * conversion resolves relative values against the classpath when a resource of that name exists.
 */
@Component
public class PersistedEvidenceProvider implements PolicyEvidenceProvider {

    /** Test classes per suite-based check (simple class name -> fully qualified name is resolved from the outcomes). */
    static final Map<String, List<String>> SUITES = new LinkedHashMap<>();

    static {
        String base = "com.agenticengineering.urlshortener.";
        SUITES.put("SEC-01", List.of(base + "shortener.domain.UrlValidationTest"));
        SUITES.put("SEC-02", List.of(base + "shortener.domain.UrlValidationTest", base + "tests.unit.FixedLocalResolverTest",
                base + "tests.unit.SharedValueObjectsTest"));
        SUITES.put("PRV-01", List.of(base + "tests.integration.RedirectEventFieldsTest", base + "shortener.analytics.AnalyticsCaptureTest"));
        SUITES.put("AUD-01", List.of(base + "tests.unit.AuditApiSurfaceTest"));
        SUITES.put("CMP-02", List.of(base + "tests.integration.PolicyBlockingTest", base + "tests.integration.DependencyPolicyDemonstrationTest"));
    }

    private final AuditChain chain;
    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final TestExecutionStageExecutor.Factory tests;
    private final Path workspace;
    private final Path pom;
    private final Path inventory;
    private final boolean suiteEnabled;
    private final boolean secretScanEnabled;
    private final String secretScanCommand;
    private final String maven;

    public PersistedEvidenceProvider(AuditChain chain, JdbcTemplate jdbc, ArtifactStore artifacts, TestExecutionStageExecutor.Factory tests,
            @Value("${app.workspace-root:.}") String workspace, @Value("${app.policy.pom-file:pom.xml}") String pom,
            @Value("${app.policy.inventory-file:docs/compliance/licenses.xml}") String inventory,
            @Value("${app.policy.evidence-suite.enabled:true}") boolean suiteEnabled,
            @Value("${app.policy.secret-scan.enabled:true}") boolean secretScanEnabled,
            @Value("${app.policy.secret-scan.command:gitleaks}") String secretScanCommand,
            @Value("${app.test-execution.maven-command:mvn}") String maven) {
        this.chain = chain;
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.tests = tests;
        this.workspace = Path.of(workspace).toAbsolutePath().normalize();
        this.pom = this.workspace.resolve(pom);
        this.inventory = this.workspace.resolve(inventory);
        this.suiteEnabled = suiteEnabled;
        this.secretScanEnabled = secretScanEnabled;
        this.secretScanCommand = secretScanCommand;
        this.maven = maven;
    }

    @Override
    public PolicyInput gather(String runId) {
        Map<String, Evidence> evidence = new HashMap<>();
        evidence.putAll(suites(runId));
        evidence.put("SEC-03", secretScanEnabled ? SecretScan.run(workspace, secretScanCommand)
                : Evidence.notOk("secret scan disabled by configuration (app.policy.secret-scan.enabled)"));
        ChainVerification verification = chain.verify(runId);
        evidence.put("AUD-02", verification.intact()
                ? Evidence.ok("chain intact: " + verification.eventCount() + " events")
                : Evidence.notOk("chain broken at " + verification.failedPosition() + " (" + verification.check() + ")"));
        evidence.put("DEP-01", DependencyInventoryCheck.evaluate(pom, inventory));
        evidence.put("DEP-02", dependencyScan());
        evidence.put("LIC-01", LicenseCheck.evaluate(inventory));
        evidence.put("CHG-01", changeApprovals(runId));
        evidence.put("CHG-02", replanRevalidation(runId));
        evidence.put("CHG-03", traceability());
        Map<String, PolicyInput.Attestation> attestations;
        try {
            attestations = AttestationReader.read(workspace.resolve("docs/attestations"));
        } catch (Exception e) {
            attestations = Map.of();
        }
        return new PolicyInput(evidence, attestations, Map.of());
    }

    private Map<String, Evidence> suites(String runId) {
        if (!suiteEnabled) {
            Map<String, Evidence> disabled = new LinkedHashMap<>();
            SUITES.keySet().forEach(check -> disabled.put(check,
                    Evidence.notOk("evidence suite disabled by configuration (app.policy.evidence-suite.enabled)")));
            return disabled;
        }
        String pattern = String.join(",", SUITES.values().stream().flatMap(List::stream)
                .map(c -> c.substring(c.lastIndexOf('.') + 1)).distinct().toList());
        try {
            tests.create(workspace, maven, pattern, Duration.ofSeconds(180)).execute(new RunContext(runId, "N12"));
        } catch (Exception e) {
            // no outcomes are recorded; every suite-based check then reports its classes as not executed
        }
        Map<String, String> worst = new HashMap<>();
        jdbc.query("SELECT SUITE, STATUS FROM TEST_OUTCOME WHERE RUN_ID = ?", rs -> {
            String suite = rs.getString("SUITE");
            String status = rs.getString("STATUS");
            worst.merge(suite, status, (a, b) -> a.equals("PASSED") ? b : a);
        }, runId);
        String operation = jdbc.queryForList("SELECT EVIDENCE_REF FROM TEST_OUTCOME WHERE RUN_ID = ? ORDER BY ID DESC LIMIT 1", String.class, runId)
                .stream().findFirst().orElse("no test run");
        return SuiteEvidence.evaluate(SUITES, worst, operation);
    }

    private Evidence dependencyScan() {
        Path report = workspace.resolve("target/dependency-check/dependency-check-report.json");
        try {
            if (Files.exists(report)) {
                return Files.readString(report).contains("\"severity\"")
                        ? Evidence.notOk(workspace.relativize(report) + ": vulnerabilities reported")
                        : Evidence.ok(workspace.relativize(report) + ": no vulnerabilities reported");
            }
        } catch (Exception e) {
            return Evidence.notOk("dependency-check report unreadable: " + e.getClass().getSimpleName());
        }
        return Evidence.degraded("no DependencyCheck report (no NVD key, docs/compliance/dep-02-degradation.md); attestation required");
    }

    private Evidence changeApprovals(String runId) {
        boolean design = approved(runId, "DESIGN_APPROVAL", "G2");
        boolean changesApproved = artifacts.latest(runId, "IMPACT_ANALYSIS").map(a -> a.content().contains("\"approved_requirement\""))
                .orElse(false);
        if (!design) {
            return Evidence.notOk("no approved design (G2) for the artifacts this run changes");
        }
        if (changesApproved && !approved(runId, "CHANGE_APPROVAL", null)) {
            return Evidence.notOk("the run changes an approved requirement without an approved CHANGE_APPROVAL (C-005)");
        }
        return Evidence.ok("approved design (G2)" + (changesApproved ? " and CHANGE_APPROVAL (C-005)" : "") + " recorded");
    }

    private boolean approved(String runId, String kind, String gate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = ? AND (KIND = ? OR (? IS NOT NULL AND GATE = ?))"
                + " AND DECISION = 'APPROVED'", Integer.class, runId, kind, gate, gate) > 0;
    }

    private Evidence replanRevalidation(String runId) {
        int events = jdbc.queryForObject("SELECT COUNT(*) FROM REPLANNING_EVENT WHERE RUN_ID = ?", Integer.class, runId);
        if (events == 0) {
            return Evidence.notApplicable("no replan trigger in this run");
        }
        int missing = jdbc.queryForObject("SELECT COUNT(*) FROM REPLANNING_EVENT WHERE RUN_ID = ? AND INVALIDATION_RECORDS IS NULL",
                Integer.class, runId);
        return missing == 0 ? Evidence.ok(events + " replan(s), each with re-validation records")
                : Evidence.notOk(missing + " of " + events + " replan(s) without re-validation records");
    }

    private Evidence traceability() {
        Path manifest = workspace.resolve("specs/001-agentic-url-shortener/traceability/mapping-manifest.yml");
        try {
            if (!Files.exists(manifest)) {
                return Evidence.notOk("mapping manifest missing: " + manifest);
            }
            // per run: the demonstration-set criteria are deferred to T090/T094 (CHANGE_APPROVAL 2026-10-22)
            ManifestChecker.RunView view = new ManifestChecker(workspace, new GitCommitLookup(workspace))
                    .check(MappingManifest.fromYaml(Files.readString(manifest))).forRun();
            ManifestChecker.Report report = view.report();
            String deferred = view.deferredToDemonstrationSet().isEmpty() ? ""
                    : "; deferred to the demonstration-set check (T090/T094): " + view.deferredToDemonstrationSet();
            return report.clean() ? Evidence.ok("mapping manifest clean in both directions" + deferred)
                    : Evidence.notOk("mapping manifest not clean: " + report.requirementsWithoutEvidence().size() + " requirements without evidence, "
                            + report.testsWithoutRequirement().size() + " tests without requirement, " + report.unknownRequirementIds().size()
                            + " unknown IDs, " + report.missingFiles().size() + " missing files");
        } catch (Exception e) {
            return Evidence.notOk("traceability check failed: " + e.getClass().getSimpleName());
        }
    }
}
