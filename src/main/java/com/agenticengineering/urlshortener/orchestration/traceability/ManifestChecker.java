package com.agenticengineering.urlshortener.orchestration.traceability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bidirectional traceability check (T105; plan section 13, CHG-03): requirement -> evidence (every defined requirement
 * has a test or an evidence file) and evidence -> requirement (every test class cites a requirement, every cited ID is
 * defined), plus integrity: referenced files exist, evidence hashes match, referenced commits exist.
 */
public final class ManifestChecker {

    public record Report(List<String> requirementsWithoutEvidence, List<String> testsWithoutRequirement, List<String> unknownRequirementIds,
            List<String> missingFiles, List<String> hashMismatches, List<String> unknownCommits) {

        public boolean clean() {
            return requirementsWithoutEvidence.isEmpty() && testsWithoutRequirement.isEmpty() && unknownRequirementIds.isEmpty()
                    && missingFiles.isEmpty() && hashMismatches.isEmpty() && unknownCommits.isEmpty();
        }

        public String describe() {
            return "requirements without evidence: " + requirementsWithoutEvidence + "\ntests without requirement: "
                    + testsWithoutRequirement + "\nunknown requirement IDs: " + unknownRequirementIds + "\nmissing files: " + missingFiles
                    + "\nhash mismatches: " + hashMismatches + "\nunknown commits: " + unknownCommits;
        }

        /**
         * The view CHG-03 judges for a single run (human CHANGE_APPROVAL 2026-10-22,
         * approvals/2026-10-22-chg03-demonstration-set.md): the criteria measured over the demonstration runs are deferred
         * to the demonstration-set check (T090/T094); everything else is unchanged.
         */
        public RunView forRun() {
            List<String> deferred = requirementsWithoutEvidence.stream().filter(DEMONSTRATION_SET_CRITERIA::contains).toList();
            List<String> remaining = requirementsWithoutEvidence.stream().filter(id -> !DEMONSTRATION_SET_CRITERIA.contains(id)).toList();
            return new RunView(new Report(remaining, testsWithoutRequirement, unknownRequirementIds, missingFiles, hashMismatches,
                    unknownCommits), deferred);
        }
    }

    /** A per-run report and the demonstration-set criteria it deferred. */
    public record RunView(Report report, List<String> deferredToDemonstrationSet) {

        public boolean clean() {
            return report.clean();
        }
    }

    /** Success criteria measured over the demonstration runs, not within one run (spec SC-002, SC-003, SC-006, SC-007). */
    public static final java.util.Set<String> DEMONSTRATION_SET_CRITERIA = java.util.Set.of("SC-002", "SC-003", "SC-006", "SC-007");

    private final Path root;
    private final CommitLookup commits;

    public ManifestChecker(Path root, CommitLookup commits) {
        this.root = root;
        this.commits = commits;
    }

    public Report check(MappingManifest manifest) throws IOException {
        List<String> catalog = RequirementCatalog.read(root.resolve(manifest.feature()).resolve("spec.md"));
        Set<String> defined = new HashSet<>(catalog);
        List<String> withoutEvidence = new ArrayList<>();
        for (String id : catalog) {
            MappingManifest.Entry entry = manifest.requirements().get(id);
            if (entry == null || (entry.tests().isEmpty() && entry.evidence().isEmpty())) {
                withoutEvidence.add(id);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String id : manifest.requirements().keySet()) {
            if (!defined.contains(id)) {
                unknown.add(id + " (manifest)");
            }
        }
        List<String> unmappedTests = new ArrayList<>();
        for (Path file : ManifestGenerator.testSources(root)) {
            String relative = ManifestGenerator.relative(root, file);
            LinkedHashSet<String> cited = RequirementCatalog.cited(Files.readString(file));
            if (cited.isEmpty()) {
                unmappedTests.add(relative);
            }
            for (String id : cited) {
                if (!defined.contains(id)) {
                    unknown.add(id + " (" + relative + ")");
                }
            }
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        List<String> mismatches = new ArrayList<>();
        LinkedHashSet<String> badCommits = new LinkedHashSet<>();
        for (MappingManifest.Entry entry : manifest.requirements().values()) {
            for (MappingManifest.FileRef ref : concat(entry.tests(), entry.docs())) {
                if (!Files.exists(root.resolve(ref.path()))) {
                    missing.add(ref.path());
                }
                if (!commits.exists(ref.commit())) {
                    badCommits.add(ref.commit());
                }
            }
            for (MappingManifest.EvidenceRef evidence : entry.evidence()) {
                Path file = root.resolve(evidence.path());
                if (!Files.exists(file)) {
                    missing.add(evidence.path());
                } else if (!sha256(Files.readAllBytes(file)).equals(evidence.sha256())) {
                    mismatches.add(evidence.path());
                }
            }
        }
        return new Report(withoutEvidence, unmappedTests, unknown, List.copyOf(missing), mismatches, List.copyOf(badCommits));
    }

    private static List<MappingManifest.FileRef> concat(List<MappingManifest.FileRef> a, List<MappingManifest.FileRef> b) {
        List<MappingManifest.FileRef> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
