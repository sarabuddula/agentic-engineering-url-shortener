package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T127 (Constitution X, C-004): replayed and simulated evidence is never presented as live or human. The run's
 * execution class (LIVE, REPLAY) is in its creation event; every approval decision event and its lineage entry carry the
 * evidence class (HUMAN, SIMULATED), so the history shows which decisions a person actually made.
 * Requirements: FR-030 (audit trail shows evidence classes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EvidenceClassTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private DecisionLineage lineage;

    @Autowired
    private WorkflowStore store;

    @Test
    void theExecutionClassIsInTheRunCreationEvent() {
        String replay = engine.createRun("api:test-engineer", "POL-2026-10-18-v1", "REPLAY");
        String live = engine.createRun("api:test-engineer", "POL-2026-10-18-v1", "LIVE");

        assertThat(creation(replay).reason()).contains("execution class REPLAY");
        assertThat(creation(live).reason()).contains("execution class LIVE");
    }

    @Test
    void approvalDecisionsCarryTheirEvidenceClass() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ApprovalService.Approval simulated = approvals.request(runId, ApprovalKind.DESIGN_APPROVAL, "N07", "test-engineer", "ADRs");
        approvals.decide(runId, simulated.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true, "ok",
                ApprovalService.EvidenceClass.SIMULATED);
        ApprovalService.Approval human = approvals.request(runId, ApprovalKind.CLARIFICATION, "N03", "test-engineer", "ambiguous");
        approvals.decide(runId, human.id(), "test-approver", KeyIdentity.Role.APPROVER, AuditEntry.ActorType.HUMAN, true, "answered");

        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("APPROVAL_DECIDED") && e.reason().contains("approval " + simulated.id())
                && e.reason().contains("[evidence SIMULATED]"));
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("APPROVAL_DECIDED") && e.reason().contains("approval " + human.id())
                && e.reason().contains("[evidence HUMAN]"));
        assertThat(lineage.evidenceClassOf(runId, simulated.id())).isEqualTo("SIMULATED");
    }

    private WorkflowStore.HistoryEntry creation(String runId) {
        return store.history(runId).stream().filter(e -> e.action().equals("RUN_CREATED")).findFirst().orElseThrow();
    }
}
