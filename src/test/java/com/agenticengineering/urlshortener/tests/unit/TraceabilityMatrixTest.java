package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.traceability.ManifestChecker;
import com.agenticengineering.urlshortener.orchestration.traceability.MappingManifest;
import com.agenticengineering.urlshortener.orchestration.traceability.TraceabilityMatrix;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T090 (plan section 13): the traceability matrix joins the mapping manifest with the exported run records (the
 * persisted run tables as read through the REVIEWER API) and renders both directions - requirement to tasks, tests and
 * evidence, grouped by family (FR, NFR, SC, SCN, US, PVT), and test or evidence file back to requirements - plus the
 * demonstration runs each evidence file stands for and the checker's orphan status. Requirements: FR-030, NFR-011.
 */
class TraceabilityMatrixTest {

    @TempDir
    Path root;

    @Test
    void bothDirectionsTheRunsAndTheOrphanStatusAreRendered() throws IOException {
        Files.createDirectories(root.resolve("docs/evidence/runs"));
        Files.writeString(root.resolve("docs/evidence/runs/run-1.json"), """
                {"run": {"run_id": "run-1", "status": "COMPLETED", "execution_class": "LIVE", "terminal_outcome": "READY"},
                 "verify": {"verified": true, "event_count": 3},
                 "release_readiness": {"policy_outcomes": [{"check_id": "SEC-01", "outcome": "PASS"}, {"check_id": "CHG-02", "outcome": "NOT_APPLICABLE"}]},
                 "history": [{"actor_type": "HUMAN", "action": "APPROVAL_DECIDED"}, {"actor_type": "SYSTEM", "action": "RUN_CREATED"}]}
                """);
        Map<String, MappingManifest.Entry> requirements = new LinkedHashMap<>();
        requirements.put("FR-001", new MappingManifest.Entry(List.of("ADR-002"), List.of("T027"),
                List.of(new MappingManifest.FileRef("src/test/java/x/CreateTest.java", "a".repeat(40))), List.of(), List.of()));
        requirements.put("NFR-001", new MappingManifest.Entry(List.of(), List.of("T027"),
                List.of(new MappingManifest.FileRef("src/test/java/x/CreateTest.java", "a".repeat(40))), List.of(), List.of()));
        requirements.put("PVT-001", new MappingManifest.Entry(List.of(), List.of("T045"), List.of(), List.of(),
                List.of(new MappingManifest.EvidenceRef("docs/evidence/runs/run-1.json", "b".repeat(64)))));
        requirements.put("US-001", new MappingManifest.Entry(List.of(), List.of(), List.of(), List.of(), List.of()));
        MappingManifest manifest = new MappingManifest("specs/001-feature", requirements);
        ManifestChecker.Report report = new ManifestChecker.Report(List.of("US-001"), List.of(), List.of(), List.of(), List.of(), List.of());

        String matrix = TraceabilityMatrix.render(manifest, report, root);

        assertThat(matrix).contains("ORPHANS FOUND").contains("US-001");
        assertThat(matrix).contains("## FR").contains("## NFR").contains("## PVT").contains("## US");
        assertThat(matrix).contains("| FR-001 | T027 | ADR-002 | CreateTest |");
        assertThat(matrix).contains("| `src/test/java/x/CreateTest.java` | FR-001, NFR-001 |");
        assertThat(matrix).contains("| `docs/evidence/runs/run-1.json` | PVT-001 |");
        assertThat(matrix).contains("| run-1 | COMPLETED | LIVE | READY | intact (3 events) | 1 PASS, 0 FAIL, 0 EXCEPTION_REQUESTED, 1 NOT_APPLICABLE | 1 | PVT-001 |");
    }
}
