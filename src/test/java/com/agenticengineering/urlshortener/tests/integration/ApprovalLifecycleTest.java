package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalExpiredException;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService.Approval;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService.Decision;
import com.agenticengineering.urlshortener.orchestration.approval.SelfApprovalDeniedException;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.engine.OperationGuard;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.shared.api.ForbiddenException;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.agenticengineering.urlshortener.tests.support.MutableTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T059: approval lifecycle for gates G1-G8 (FR-024, FR-025, FR-046, PVT-005, SC-004; ADR-008 gate-role mapping):
 * PENDING -> APPROVED | REJECTED | ESCALATED (one re-issue with an equal window) -> expired (path safe-stops);
 * self-approval and agent approval denied, audited, approval left pending; silence is never approval.
 */
@JdbcTest
@Import({ApprovalService.class, WorkflowStore.class, AuditChain.class, OperationGuard.class, ApprovalLifecycleTest.Clock.class})
class ApprovalLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-10-22T09:00:00Z");

    @TestConfiguration
    static class Clock {
        @Bean
        @Primary
        MutableTimeSource mutableTimeSource() {
            return new MutableTimeSource(T0);
        }

        @Bean
        TimeSource timeSource(MutableTimeSource clock) {
            return clock;
        }
    }

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private MutableTimeSource clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRun() {
        clock.set(T0);
        store.createRun("run-appr", "api:test-engineer", "POL-2026-10-18-v1", SdlcWorkflow.graph());
    }

    @Test
    void requestCreatesAPendingApprovalWithAFiveMinuteWindow() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        assertThat(approval.status()).isEqualTo(Decision.PENDING);
        assertThat(approval.gate()).isEqualTo("G2");
        assertThat(approval.timeoutAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)));
        assertThat(lastAction()).isEqualTo("APPROVAL_REQUESTED");
    }

    @Test
    void aDistinctApproverWithTheRightRoleApproves() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");
        clock.advance(Duration.ofMinutes(1));

        Approval decided = approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "ADRs reviewed");

        assertThat(decided.status()).isEqualTo(Decision.APPROVED);
        assertThat(decided.approverKeyId()).isEqualTo("test-approver");
        assertThat(decided.decidedAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
        assertThat(jdbc.queryForObject("SELECT EVIDENCE_CLASS FROM APPROVAL WHERE ID = ?", String.class, approval.id())).isEqualTo("HUMAN");
        WorkflowStore.HistoryEntry audit = store.history("run-appr").get(store.history("run-appr").size() - 1);
        assertThat(audit.action()).isEqualTo("APPROVAL_DECIDED");
        assertThat(audit.actorType()).isEqualTo("HUMAN");
        assertThat(audit.result()).isEqualTo("APPROVED");
    }

    @Test
    void aRejectionIsFinalAndNeverRetried() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, false,
                "design does not satisfy FR-012");

        assertThat(approvals.find("run-appr", approval.id()).status()).isEqualTo(Decision.REJECTED);
        assertThatThrownBy(() -> approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "changed my mind")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APPROVAL WHERE RUN_ID = 'run-appr'", Integer.class)).isEqualTo(1);
    }

    @Test
    void selfApprovalIsDeniedAuditedAndLeftPending() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-approver", "requested by an approver");

        assertThatThrownBy(() -> approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "self")).isInstanceOf(SelfApprovalDeniedException.class);

        assertThat(approvals.find("run-appr", approval.id()).status()).isEqualTo(Decision.PENDING);
        assertThat(lastAction()).isEqualTo("SELF_APPROVAL_DENIED");
    }

    @Test
    void agentApprovalIsDeniedAuditedAndLeftPending() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");

        assertThatThrownBy(() -> approvals.decide("run-appr", approval.id(), "agent:N07", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.AGENT, true, "agent tries")).isInstanceOf(SelfApprovalDeniedException.class);

        assertThat(approvals.find("run-appr", approval.id()).status()).isEqualTo(Decision.PENDING);
        assertThat(lastAction()).isEqualTo("AGENT_APPROVAL_DENIED");
    }

    @Test
    void gateRoleMappingFollowsAdr008() {
        for (ApprovalKind kind : List.of(ApprovalKind.CLARIFICATION, ApprovalKind.DESIGN_APPROVAL, ApprovalKind.SECURITY_SENSITIVE,
                ApprovalKind.DESTRUCTIVE_ACTION)) {
            assertThat(kind.requiredRole()).as(kind.name()).isEqualTo(KeyIdentity.Role.APPROVER);
        }
        for (ApprovalKind kind : List.of(ApprovalKind.POLICY_EXCEPTION, ApprovalKind.ACCEPTED_LIMITATION,
                ApprovalKind.RELEASE_AUTHORIZATION, ApprovalKind.FINAL_SUBMISSION)) {
            assertThat(kind.requiredRole()).as(kind.name()).isEqualTo(KeyIdentity.Role.RELEASE_OWNER);
        }
        Approval release = approvals.request("run-appr", ApprovalKind.RELEASE_AUTHORIZATION, "N13", "test-engineer", "release");
        assertThat(release.gate()).isEqualTo("G7");
        assertThatThrownBy(() -> approvals.decide("run-appr", release.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "wrong role")).isInstanceOf(ForbiddenException.class);
        assertThat(approvals.find("run-appr", release.id()).status()).isEqualTo(Decision.PENDING);
    }

    @Test
    void firstTimeoutEscalatesOnceWithAnEqualSecondWindow() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");
        clock.advance(Duration.ofMinutes(5));

        List<Approval> expired = approvals.checkTimeouts();

        assertThat(expired).isEmpty();
        Approval escalated = approvals.find("run-appr", approval.id());
        assertThat(escalated.status()).isEqualTo(Decision.ESCALATED);
        assertThat(escalated.secondWindowTimeoutAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(lastAction()).isEqualTo("APPROVAL_ESCALATED");
        clock.advance(Duration.ofMinutes(2));
        assertThat(approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "approved after escalation").status()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void secondTimeoutExpiresTheApprovalSilenceIsNeverApproval() {
        Approval approval = approvals.request("run-appr", ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs proposed");
        clock.advance(Duration.ofMinutes(5));
        approvals.checkTimeouts();
        clock.advance(Duration.ofMinutes(5));

        List<Approval> expired = approvals.checkTimeouts();

        assertThat(expired).extracting(Approval::id).containsExactly(approval.id());
        assertThat(expired.get(0).node()).isEqualTo("N07");
        assertThat(lastAction()).isEqualTo("APPROVAL_EXPIRED");
        assertThatThrownBy(() -> approvals.decide("run-appr", approval.id(), "test-approver", KeyIdentity.Role.APPROVER,
                AuditEntry.ActorType.HUMAN, true, "too late")).isInstanceOf(ApprovalExpiredException.class);
        assertThat(approvals.find("run-appr", approval.id()).status()).isNotEqualTo(Decision.APPROVED);
        assertThat(approvals.checkTimeouts()).as("expiry is reported once").isEmpty();
    }

    private String lastAction() {
        List<WorkflowStore.HistoryEntry> history = store.history("run-appr");
        return history.get(history.size() - 1).action();
    }
}
