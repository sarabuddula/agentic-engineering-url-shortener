package com.agenticengineering.urlshortener.tests.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.engine.EdgeKind;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.stages.Decomposer;
import com.agenticengineering.urlshortener.orchestration.stages.OpenAmbiguityException;
import com.agenticengineering.urlshortener.orchestration.stages.QualityReport;
import com.agenticengineering.urlshortener.orchestration.stages.RequirementQualityChecker;
import com.agenticengineering.urlshortener.tests.support.ScenarioFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T055: requirement-quality checks, ambiguity detection and decomposition (FR-034, FR-035, FR-036) on the SCN
 * fixtures. Material ambiguity follows spec Appendix A: two or more interpretations that change contracts, schemas or
 * acceptance criteria, or a missing subject, action or acceptance condition. The checker is a rule-based heuristic
 * validated on these fixtures only (disclosed limitation, T056).
 */
class RequirementIngestionTest {

    private final RequirementQualityChecker checker = new RequirementQualityChecker();
    private final Decomposer decomposer = new Decomposer();

    @Test
    void wellDefinedGreenfieldRequirementProceedsWithoutClarification() {
        QualityReport report = checker.check(ScenarioFixtures.scnA());

        assertThat(report.materialAmbiguity()).isFalse();
        assertThat(report.checks()).containsOnlyKeys("completeness", "consistency", "testability", "policy_fit");
        assertThat(report.checks().values()).containsOnly(QualityReport.Outcome.PASS);
        assertThat(report.rationale()).contains("no clarification required");
        assertThat(report.selectedBranches()).isEmpty();
        assertThat(report.kind()).isEqualTo(QualityReport.Kind.NEW_CAPABILITY);
    }

    @Test
    void ambiguousRequirementRaisesClarificationWithInterpretations() {
        QualityReport report = checker.check(ScenarioFixtures.scnC());

        assertThat(report.materialAmbiguity()).isTrue();
        assertThat(report.selectedBranches()).containsExactly("N03");
        assertThat(report.findings()).extracting(QualityReport.Finding::term).contains("support", "links");
        assertThat(report.findings()).allSatisfy(f -> {
            assertThat(f.id()).matches("AMB-\\d+");
            assertThat(f.interpretations()).hasSizeGreaterThanOrEqualTo(2);
        });
        assertThat(report.checks()).containsEntry("testability", QualityReport.Outcome.FAIL);
    }

    @Test
    void changeToExistingBehaviourSelectsImpactAnalysis() {
        QualityReport report = checker.check(ScenarioFixtures.scnB());

        assertThat(report.materialAmbiguity()).isFalse();
        assertThat(report.kind()).isEqualTo(QualityReport.Kind.CHANGE_REQUEST);
        assertThat(report.selectedBranches()).containsExactly("N04");
    }

    @Test
    void conflictingRequirementIsAMaterialInconsistency() {
        QualityReport report = checker.check("Add a read-only endpoint that deletes expired short links.");

        assertThat(report.checks()).containsEntry("consistency", QualityReport.Outcome.FAIL);
        assertThat(report.materialAmbiguity()).isTrue();
        assertThat(report.selectedBranches()).containsExactly("N03");
    }

    @Test
    void blankInputIsRejectedAsMalformed() {
        assertThatThrownBy(() -> checker.check("   ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
    }

    @Test
    void greenfieldDecompositionHasExplicitAcyclicDependencies() {
        List<TaskSpec> tasks = decomposer.decompose(checker.check(ScenarioFixtures.scnA()), "SCN-A");

        assertThat(tasks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(tasks.get(0).dependsOn()).isEmpty();
        assertThat(tasks).allSatisfy(t -> assertThat(t.requirementRefs()).contains("SCN-A"));
        StageGraph.Builder graph = StageGraph.builder();
        tasks.forEach(t -> graph.node(t.key()));
        tasks.forEach(t -> t.dependsOn().forEach(d -> graph.edge(d, t.key(), EdgeKind.DEPENDENCY)));
        assertThat(graph.build().nodes()).hasSize(tasks.size()); // build() rejects cycles and unknown keys
        TaskSpec tests = tasks.stream().filter(t -> t.title().toLowerCase().contains("test")).findFirst().orElseThrow();
        assertThat(tests.dependsOn()).as("tests follow implementation").isNotEmpty();
    }

    @Test
    void decompositionIsRefusedWhileAnAmbiguityIsOpen() {
        QualityReport ambiguous = checker.check(ScenarioFixtures.scnC());

        assertThatThrownBy(() -> decomposer.decompose(ambiguous, "SCN-C")).isInstanceOf(OpenAmbiguityException.class);
    }
}
