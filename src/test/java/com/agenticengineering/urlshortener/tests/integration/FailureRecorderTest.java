package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.reliability.FailureEvent;
import com.agenticengineering.urlshortener.orchestration.reliability.FailureRecorder;
import com.agenticengineering.urlshortener.orchestration.reliability.RecoveryMechanism;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T048: MTTR capture foundation (FR-031, plan section 7, data-model FAILURE_EVENT). Captures detection, recovery start
 * and completion, mechanism, recovered flag, governance wait (reported separately) and MTTR exclusion with reason.
 */
@JdbcTest
@Import(FailureRecorder.class)
class FailureRecorderTest {

    private static final Instant DETECTED = Instant.parse("2026-10-21T12:00:00Z");

    @Autowired
    private FailureRecorder recorder;

    @Autowired
    private JdbcTemplate jdbc;

    private long nodeId;

    @BeforeEach
    void seedRunAndNode() {
        jdbc.update("INSERT INTO WORKFLOW_RUN (ID, REQUIREMENT_REF, POLICY_VERSION, STATUS, RUN_REVISION, VERSION,"
                + " AUDIT_EVENT_COUNT, CREATED_AT, EXECUTION_CLASS, REPLAN_COUNT) VALUES ('run-1', 'req-1',"
                + " 'POL-2026-10-18-v1', 'RUNNING', 0, 0, 0, ?, 'LIVE', 0)", Timestamp.from(DETECTED));
        jdbc.update("INSERT INTO STAGE_NODE (RUN_ID, NODE_KEY, STATUS, ATTEMPT_COUNT, RUN_REVISION, VERSION)"
                + " VALUES ('run-1', 'N09', 'RUNNING', 1, 0, 0)");
        nodeId = jdbc.queryForObject("SELECT ID FROM STAGE_NODE WHERE NODE_KEY = 'N09'", Long.class);
    }

    @Test
    void recoveredFailurePersistsEveryCaptureField() {
        long id = recorder.detected("run-1", nodeId, DETECTED);
        recorder.recoveryStarted(id, DETECTED.plusMillis(200), RecoveryMechanism.FP_1_RETRY);
        recorder.addGovernanceWait(id, Duration.ofSeconds(30));
        recorder.recovered(id, DETECTED.plusMillis(1700));

        FailureEvent event = recorder.find(id);
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.nodeId()).isEqualTo(nodeId);
        assertThat(event.failureDetectedAt()).isEqualTo(DETECTED);
        assertThat(event.recoveryStartAt()).isEqualTo(DETECTED.plusMillis(200));
        assertThat(event.recoveryCompleteAt()).isEqualTo(DETECTED.plusMillis(1700));
        assertThat(event.mechanism()).isEqualTo(RecoveryMechanism.FP_1_RETRY);
        assertThat(event.recovered()).isTrue();
        assertThat(event.governanceWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(event.mttrExcluded()).isFalse();
        assertThat(event.recoveryDuration()).contains(Duration.ofMillis(1700));
        assertThat(jdbc.queryForObject("SELECT RECOVERY_MECHANISM FROM FAILURE_EVENT WHERE ID = ?", String.class, id))
                .isEqualTo("FP-1");
    }

    @Test
    void unrecoveredFailureHasNoCompletionOrDuration() {
        long id = recorder.detected("run-1", nodeId, DETECTED);
        recorder.recoveryStarted(id, DETECTED.plusSeconds(1), RecoveryMechanism.FP_4_SAFE_STOP);

        FailureEvent event = recorder.find(id);
        assertThat(event.recovered()).isFalse();
        assertThat(event.recoveryCompleteAt()).isNull();
        assertThat(event.recoveryDuration()).isEmpty();
    }

    @Test
    void governanceWaitAccumulatesAndStaysOutOfTheRecoveryDuration() {
        long id = recorder.detected("run-1", nodeId, DETECTED);
        recorder.recoveryStarted(id, DETECTED, RecoveryMechanism.FP_4_SAFE_STOP);
        recorder.addGovernanceWait(id, Duration.ofMinutes(2));
        recorder.addGovernanceWait(id, Duration.ofMinutes(3));
        recorder.recovered(id, DETECTED.plusSeconds(400));

        FailureEvent event = recorder.find(id);
        assertThat(event.governanceWait()).isEqualTo(Duration.ofMinutes(5));
        assertThat(event.recoveryDuration()).contains(Duration.ofSeconds(400));
    }

    @Test
    void exclusionFromMttrRequiresAReason() {
        long id = recorder.detected("run-1", nodeId, DETECTED);

        assertThatThrownBy(() -> recorder.excludeFromMttr(id, " ")).isInstanceOf(IllegalArgumentException.class);
        recorder.excludeFromMttr(id, "injected fault outside the measurement population");
        assertThat(recorder.find(id).mttrExcluded()).isTrue();
        assertThat(recorder.find(id).mttrExclusionReason()).isEqualTo("injected fault outside the measurement population");
    }

    @Test
    void timestampsMustMoveForwardAndRecoveryNeedsAStart() {
        long id = recorder.detected("run-1", nodeId, DETECTED);

        assertThatThrownBy(() -> recorder.recovered(id, DETECTED.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> recorder.recoveryStarted(id, DETECTED.minusSeconds(1), RecoveryMechanism.FP_2_FALLBACK))
                .isInstanceOf(IllegalArgumentException.class);
        recorder.recoveryStarted(id, DETECTED.plusSeconds(1), RecoveryMechanism.FP_2_FALLBACK);
        assertThatThrownBy(() -> recorder.recovered(id, DETECTED)).isInstanceOf(IllegalArgumentException.class);
    }
}
