package com.agenticengineering.urlshortener.tests.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.reliability.FailureEvent;
import com.agenticengineering.urlshortener.orchestration.reliability.MttrCalculator;
import com.agenticengineering.urlshortener.orchestration.reliability.MttrReport;
import com.agenticengineering.urlshortener.orchestration.reliability.RecoveryMechanism;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T079 (FR-031, PVT-008; constitution Evidence Constraints; plan section 7; human decision 2026-10-19): MTTR = total
 * recovery duration of recovered failures / number of recovered failures, governance wait excluded from the primary
 * figure and reported separately with a labelled secondary MTTR including it; unrecovered failures reported separately
 * and excluded; excluded events listed with their reason; N/A (not zero) with no recovered failure.
 */
class MttrTest {

    private static final Instant T0 = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void mixedRecoveredAndUnrecoveredFailures() {
        MttrReport report = MttrCalculator.compute(List.of(
                recovered(1, Duration.ofSeconds(1), Duration.ZERO, RecoveryMechanism.FP_1_RETRY),
                recovered(2, Duration.ofSeconds(5), Duration.ofSeconds(3), RecoveryMechanism.FP_2_FALLBACK),
                unrecovered(3),
                excluded(4, "fault injected outside the measured window")));

        assertThat(report.recoveredCount()).isEqualTo(2);
        assertThat(report.totalRecovery()).isEqualTo(Duration.ofSeconds(3));
        assertThat(report.mttr()).contains(Duration.ofMillis(1500));
        assertThat(report.totalGovernanceWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(report.mttrIncludingGovernanceWait()).contains(Duration.ofSeconds(3));
        assertThat(report.unrecoveredCount()).isEqualTo(1);
        assertThat(report.excluded()).containsExactly("failure 4: fault injected outside the measured window");
        assertThat(report.byMechanism()).containsEntry("FP-1", 1).containsEntry("FP-2", 1);
    }

    @Test
    void withNoRecoveredFailureMttrIsNotApplicableNotZero() {
        MttrReport report = MttrCalculator.compute(List.of(unrecovered(1)));

        assertThat(report.mttr()).isEmpty();
        assertThat(report.mttrIncludingGovernanceWait()).isEmpty();
        assertThat(report.unrecoveredCount()).isEqualTo(1);
        assertThat(report.describe()).contains("MTTR: N/A");
    }

    @Test
    void aGovernanceWaitLongerThanTheEpisodeIsADataError() {
        assertThatThrownBy(() -> MttrCalculator.compute(List.of(recovered(1, Duration.ofSeconds(1), Duration.ofSeconds(2),
                RecoveryMechanism.FP_1_RETRY)))).isInstanceOf(IllegalStateException.class).hasMessageContaining("failure 1");
    }

    private static FailureEvent recovered(long id, Duration episode, Duration governanceWait, RecoveryMechanism mechanism) {
        return new FailureEvent(id, "run-1", 10, T0, T0.plusMillis(100), T0.plus(episode), mechanism, true, governanceWait, false, null);
    }

    private static FailureEvent unrecovered(long id) {
        return new FailureEvent(id, "run-1", 10, T0, null, null, null, false, Duration.ZERO, false, null);
    }

    private static FailureEvent excluded(long id, String reason) {
        return new FailureEvent(id, "run-1", 10, T0, T0.plusMillis(10), T0.plusSeconds(9), RecoveryMechanism.FP_1_RETRY, true,
                Duration.ZERO, true, reason);
    }
}
