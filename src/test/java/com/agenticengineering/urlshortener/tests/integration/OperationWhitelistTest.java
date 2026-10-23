package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.approval.ApprovalService;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.OperationWhitelist;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CHK047 (plan section 3 per-node executor whitelist, amended and enforced by human decision 2026-10-22): while a stage
 * executor runs, an operation outside its node's list is refused, audited (actor AGENT, result BLOCKED) and fails the
 * stage; permitted operations proceed; operations started outside any executor (people through the API) are not
 * subject to it. Requirements: FR-020, FR-030.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OperationWhitelistTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private WorkflowStore store;

    @Test
    void theWhitelistMatchesThePlanWithTheAmendments() {
        assertThat(OperationWhitelist.allowed("N04")).containsExactlyInAnyOrder(OperationWhitelist.Op.AR, OperationWhitelist.Op.AW,
                OperationWhitelist.Op.AQ);
        assertThat(OperationWhitelist.allowed("N09")).containsExactlyInAnyOrder(OperationWhitelist.Op.AR, OperationWhitelist.Op.TS);
        assertThat(OperationWhitelist.allowed("N12")).containsExactlyInAnyOrder(OperationWhitelist.Op.AR, OperationWhitelist.Op.PE,
                OperationWhitelist.Op.TS);
        assertThat(OperationWhitelist.allowed("N13")).containsExactlyInAnyOrder(OperationWhitelist.Op.AR, OperationWhitelist.Op.AQ);
    }

    @Test
    void anArtifactWriteFromTheTestStageIsBlockedAndAudited() {
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N09", ctx -> {
            artifacts.record(ctx.runId(), "DESIGN", "written by the wrong stage", "DETERMINISTIC", "N09");
            return StageResult.succeeded();
        });

        String runId = run(executors);

        assertThat(store.nodeStatuses(runId).get("N09")).isEqualTo(NodeStatus.SAFE_STOPPED);
        assertThat(artifacts.latest(runId, "DESIGN")).isEmpty();
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("OPERATION_BLOCKED") && e.actorType().equals("AGENT")
                && e.result().equals("BLOCKED") && "N09".equals(e.affected()) && e.reason().contains("AW"));
    }

    @Test
    void anApprovalRequestFromTheDesignStageIsBlocked() {
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", ctx -> {
            approvals.request(ctx.runId(), ApprovalKind.DESIGN_APPROVAL, "N06", "test-engineer", "not N06's operation");
            return StageResult.succeeded();
        });

        String runId = run(executors);

        assertThat(store.nodeStatuses(runId).get("N06")).isEqualTo(NodeStatus.SAFE_STOPPED);
        assertThat(approvals.pending(runId)).isEmpty();
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("OPERATION_BLOCKED") && e.reason().contains("AQ"));
    }

    @Test
    void permittedOperationsProceedAndApiCallsAreNotSubjectToIt() {
        Map<String, StageExecutor> executors = fixtures();
        executors.put("N06", ctx -> {
            artifacts.record(ctx.runId(), "DESIGN", "design v1", "DETERMINISTIC", "N06");
            return StageResult.succeeded();
        });
        String runId = run(executors);

        approvals.request(runId, ApprovalKind.POLICY_EXCEPTION, "N12", "test-engineer", "requested through the API");

        assertThat(artifacts.latest(runId, "DESIGN")).isPresent();
        assertThat(approvals.pending(runId)).hasSize(1);
        assertThat(store.history(runId)).noneMatch(e -> e.action().equals("OPERATION_BLOCKED"));
    }

    private String run(Map<String, StageExecutor> executors) {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        engine.execute(runId, executors, task -> StageResult.succeeded());
        return runId;
    }

    private Map<String, StageExecutor> fixtures() {
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
        executors.put("N12", ctx -> StageResult.succeeded(Set.of()));
        executors.remove("N08");
        return executors;
    }
}
