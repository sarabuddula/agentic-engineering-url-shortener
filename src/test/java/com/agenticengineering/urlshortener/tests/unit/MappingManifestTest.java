package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.traceability.CommitLookup;
import com.agenticengineering.urlshortener.orchestration.traceability.ManifestChecker;
import com.agenticengineering.urlshortener.orchestration.traceability.ManifestGenerator;
import com.agenticengineering.urlshortener.orchestration.traceability.MappingManifest;
import com.agenticengineering.urlshortener.orchestration.traceability.RequirementCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T105 (CHG-03, FR-030; plan section 13): the mapping manifest records per requirement ID its ADRs, tasks, tests and
 * documentation with the SHA of the commit that last touched each (never the manifest's own), plus evidence files with
 * SHA-256 hashes; the checker reports orphans in both directions. A small fixture repository stands in for the real one.
 */
class MappingManifestTest {

    private static final CommitLookup COMMITS = new CommitLookup() {
        @Override
        public Optional<String> lastCommit(Path relative) {
            return Optional.of("a".repeat(40));
        }

        @Override
        public boolean exists(String sha) {
            return sha.equals("a".repeat(40));
        }
    };

    /** An undefined ID, assembled so this source file does not itself cite it. */
    private static final String GHOST = "FR-" + "999";

    @TempDir
    Path root;

    @BeforeEach
    void fixtureRepository() throws IOException {
        write("specs/001-feature/spec.md", """
                - **FR-001** [Confirmed]: create links.
                - **FR-002** [Confirmed]: resolve links.
                - **NFR-001 Security**: validate input.
                - PVT-001: success rate at least 99%.
                """);
        write("specs/001-feature/tasks.md", """
                - [X] T027 [P1] create endpoint | Req: FR-001, NFR-001 | ADR: ADR-002 | Prereq: T026
                """);
        write("src/test/java/x/CreateTest.java", "/** FR-001, NFR-001. */ class CreateTest {}");
        write("src/test/java/x/support/Helper.java", "class Helper {}");
        write("src/test/java/x/package-info.java", "package x;");
    }

    /**
     * CHG-03 per run (human CHANGE_APPROVAL 2026-10-22, approvals/2026-10-22-chg03-demonstration-set.md): SC-002, SC-003,
     * SC-006 and SC-007 are measured over the demonstration runs, so a single run's check defers them to the
     * demonstration-set check; any other gap still fails, and the full report stays strict.
     */
    @Test
    void thePerRunViewDefersOnlyTheDemonstrationSetCriteria() {
        ManifestChecker.Report onlyDeferred = new ManifestChecker.Report(List.of("SC-002", "SC-003", "SC-006", "SC-007"), List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThat(onlyDeferred.clean()).isFalse();
        assertThat(onlyDeferred.forRun().clean()).isTrue();
        assertThat(onlyDeferred.forRun().deferredToDemonstrationSet()).containsExactly("SC-002", "SC-003", "SC-006", "SC-007");

        ManifestChecker.Report otherGap = new ManifestChecker.Report(List.of("SC-002", "FR-001"), List.of(), List.of(), List.of(),
                List.of(), List.of());
        assertThat(otherGap.forRun().clean()).isFalse();
        assertThat(otherGap.forRun().report().requirementsWithoutEvidence()).containsExactly("FR-001");

        ManifestChecker.Report integrityGap = new ManifestChecker.Report(List.of("SC-003"), List.of(), List.of(), List.of(),
                List.of("docs/x.md"), List.of());
        assertThat(integrityGap.forRun().clean()).isFalse();
    }

    @Test
    void theCatalogListsEveryDefinedRequirement() throws IOException {
        assertThat(RequirementCatalog.read(root.resolve("specs/001-feature/spec.md"))).containsExactly("FR-001", "FR-002", "NFR-001", "PVT-001");
    }

    @Test
    void theGeneratorMapsRequirementsToAdrsTasksTestsAndDocs() throws IOException {
        MappingManifest manifest = generator().generate();

        MappingManifest.Entry fr001 = manifest.requirements().get("FR-001");
        assertThat(fr001.adrs()).containsExactly("ADR-002");
        assertThat(fr001.tasks()).containsExactly("T027");
        assertThat(fr001.tests()).extracting(MappingManifest.FileRef::path).containsExactly("src/test/java/x/CreateTest.java");
        assertThat(fr001.tests().get(0).commit()).isEqualTo("a".repeat(40));
        assertThat(fr001.docs()).extracting(MappingManifest.FileRef::path).containsExactly("specs/001-feature/spec.md");
        assertThat(MappingManifest.fromYaml(manifest.toYaml())).isEqualTo(manifest);
    }

    @Test
    void aLargeManifestIsWrittenWithoutAliases() throws IOException {
        StringBuilder spec = new StringBuilder();
        for (int i = 1; i <= 80; i++) {
            spec.append("- **FR-").append(String.format("%03d", i)).append("** [Confirmed]: requirement.\n");
        }
        write("specs/001-feature/spec.md", spec.toString());

        String yaml = generator().generate().toYaml();

        assertThat(yaml).doesNotContain("&id").doesNotContain("*id");
        assertThat(MappingManifest.fromYaml(yaml).requirements()).hasSize(80);
    }

    @Test
    void orphansAreReportedInBothDirections() throws IOException {
        write("src/test/java/x/UnmappedTest.java", "class UnmappedTest {}");
        write("src/test/java/x/GhostIdTest.java", "/** " + GHOST + " */ class GhostIdTest {}");
        write("docs/evidence/run-1.json", "{\"ok\":true}");
        MappingManifest generated = generator().generate();
        MappingManifest manifest = generated.withEvidence("FR-001", List.of(
                new MappingManifest.EvidenceRef("docs/evidence/run-1.json", "0".repeat(64)),
                new MappingManifest.EvidenceRef("docs/evidence/missing.json", "0".repeat(64))))
                .withTest("FR-002", new MappingManifest.FileRef("src/test/java/x/DeletedTest.java", "b".repeat(40)));

        ManifestChecker.Report report = new ManifestChecker(root, COMMITS).check(manifest);

        assertThat(report.requirementsWithoutEvidence()).containsExactly("PVT-001");
        assertThat(report.testsWithoutRequirement()).containsExactly("src/test/java/x/UnmappedTest.java");
        assertThat(report.unknownRequirementIds()).containsExactly(GHOST + " (src/test/java/x/GhostIdTest.java)");
        assertThat(report.missingFiles()).containsExactlyInAnyOrder("src/test/java/x/DeletedTest.java", "docs/evidence/missing.json");
        assertThat(report.hashMismatches()).containsExactly("docs/evidence/run-1.json");
        assertThat(report.unknownCommits()).containsExactly("b".repeat(40));
        assertThat(report.clean()).isFalse();
    }

    @Test
    void aFullyMappedRepositoryIsClean() throws IOException {
        write("src/test/java/x/ResolveTest.java", "/** FR-002, PVT-001 */ class ResolveTest {}");

        ManifestChecker.Report report = new ManifestChecker(root, COMMITS).check(generator().generate());

        assertThat(report.clean()).as(report.describe()).isTrue();
    }

    private ManifestGenerator generator() {
        return new ManifestGenerator(root, Path.of("specs/001-feature"), COMMITS);
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
