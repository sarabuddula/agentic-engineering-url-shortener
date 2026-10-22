package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.persistence.ReleaseDecisionStore;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Blocking reasons always fit RELEASE_DECISION.BLOCKING_REASONS (VARCHAR 2048) and never silently drop a count. */
class BlockingReasonsFitTest {

    @Test
    void shortListsAreKeptOnePerLine() {
        assertThat(ReleaseDecisionStore.fitReasons(List.of("DEP-01 FAIL: x", "SEC-01 FAIL: y"))).isEqualTo("DEP-01 FAIL: x\nSEC-01 FAIL: y");
    }

    @Test
    void longListsAreSummarizedWithinTheColumn() {
        List<String> reasons = Collections.nCopies(40, "CHK-00 FAIL: " + "e".repeat(90));

        String fitted = ReleaseDecisionStore.fitReasons(reasons);

        assertThat(fitted.length()).isLessThanOrEqualTo(2048);
        assertThat(fitted).endsWith(" more; see POLICY_EVALUATION");
        int kept = fitted.split("\n").length - 1;
        assertThat(fitted).contains("... " + (40 - kept) + " more");
    }

    @Test
    void embeddedNewlinesCannotSplitAReason() {
        assertThat(ReleaseDecisionStore.fitReasons(List.of("a\nb"))).isEqualTo("a b");
    }
}
