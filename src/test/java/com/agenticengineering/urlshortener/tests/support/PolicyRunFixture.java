package com.agenticengineering.urlshortener.tests.support;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.policy.Evidence;
import com.agenticengineering.urlshortener.orchestration.policy.PolicyInput;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.stages.PolicyStageExecutor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives a run to the policy stage with labelled fixture stages: real gates at N07 and N13, the real N12 executor fed
 * by fixture evidence, every other stage a fixture that succeeds. Human decisions here are executed by the test and
 * recorded as SIMULATED evidence (T127). Tests bring it in with @Import.
 */
public class PolicyRunFixture {

    public static final List<String> AUTOMATED = List.of("SEC-01", "SEC-02", "SEC-03", "CMP-02", "PRV-01", "AUD-01", "AUD-02",
            "DEP-01", "DEP-02", "LIC-01", "CHG-01", "CHG-02", "CHG-03");

    private final OrchestrationEngine engine;
    private final ApprovalService approvals;
    private final TaskStore tasks;
    private final GateExecutor.Factory gates;
    private final PolicyStageExecutor.Factory policyStages;

    public PolicyRunFixture(OrchestrationEngine engine, ApprovalService approvals, TaskStore tasks, GateExecutor.Factory gates,
            PolicyStageExecutor.Factory policyStages) {
        this.engine = engine;
        this.approvals = approvals;
        this.tasks = tasks;
        this.gates = gates;
        this.policyStages = policyStages;
    }

    /** Every automated check passes; PRV-02 and LIC-02 attested. */
    public static Map<String, Evidence> goodEvidence() {
        Map<String, Evidence> evidence = new HashMap<>();
        AUTOMATED.forEach(id -> evidence.put(id, Evidence.ok("fixture:" + id)));
        return evidence;
    }

    /** Creates a run and drives it to the design gate (N07 awaiting approval). */
    public String runToDesignGate(Map<String, Evidence> evidence) {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors(evidence), task -> StageResult.succeeded());
        return runId;
    }

    /** A distinct APPROVER approves the design gate and the run continues through N12. */
    public void passDesignGate(String runId, Map<String, Evidence> evidence) {
        decide(runId, ApprovalKind.DESIGN_APPROVAL, "test-approver", KeyIdentity.Role.APPROVER, true);
        engine.resume(runId, executors(evidence), task -> StageResult.succeeded());
    }

    /** Runs to N12 and past it: either the release gate is offered or the run ends NOT READY. */
    public String runThroughPolicy(Map<String, Evidence> evidence) {
        String runId = runToDesignGate(evidence);
        passDesignGate(runId, evidence);
        return runId;
    }

    /** A distinct RELEASE_OWNER decides the release gate and the run resumes. */
    public long decideRelease(String runId, Map<String, Evidence> evidence, boolean approve) {
        long id = decide(runId, ApprovalKind.RELEASE_AUTHORIZATION, "test-release-owner", KeyIdentity.Role.RELEASE_OWNER, approve);
        engine.resume(runId, executors(evidence), task -> StageResult.succeeded());
        return id;
    }

    private long decide(String runId, ApprovalKind kind, String keyId, KeyIdentity.Role role, boolean approve) {
        ApprovalService.Approval pending = approvals.pending(runId).stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
        approvals.decide(runId, pending.id(), keyId, role, AuditEntry.ActorType.HUMAN, approve, approve ? "approved" : "rejected",
                ApprovalService.EvidenceClass.SIMULATED);
        return pending.id();
    }

    private Map<String, StageExecutor> executors(Map<String, Evidence> evidence) {
        PolicyInput input = new PolicyInput(evidence, Map.of(
                "PRV-02", new PolicyInput.Attestation("test-release-owner", "fixture"),
                "LIC-02", new PolicyInput.Attestation("test-release-owner", "fixture")), Map.of());
        Map<String, StageExecutor> executors = new HashMap<>();
        for (String node : SdlcWorkflow.graph().nodes()) {
            executors.put(node, ctx -> StageResult.succeeded());
        }
        executors.put("N02", ctx -> StageResult.succeeded(Set.of()));
        executors.put("N05", ctx -> {
            if (tasks.statuses(ctx.runId()).isEmpty()) {
                tasks.saveDecomposition(ctx.runId(), List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of())));
            }
            return StageResult.succeeded();
        });
        executors.put("N07", gates.gate(ApprovalKind.DESIGN_APPROVAL));
        executors.put("N12", policyStages.withEvidence(runId -> input));
        executors.put("N13", gates.gate(ApprovalKind.RELEASE_AUTHORIZATION));
        executors.remove("N08");
        return executors;
    }
}
