package com.agenticengineering.urlshortener.tests.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.engine.IllegalTransitionException;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StateMachines;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T051: structural legality of node and run transitions, exactly as data-model.md "State Transitions" and plan
 * section 3 define them (FR-020, FR-021, FR-025, SC-004). The legal sets are written out here independently of the
 * implementation and every other pair must be rejected. Retry timing belongs to T074/T075; HELD is excluded because
 * replan staging is deferred (L-SC-03).
 */
class NodeTransitionTest {

    /** Node: data-model "Node:" line, verbatim arrows. */
    private static final Map<NodeStatus, Set<NodeStatus>> LEGAL_NODE = Map.of(
            NodeStatus.PENDING, EnumSet.of(NodeStatus.READY, NodeStatus.NOT_APPLICABLE),
            NodeStatus.READY, EnumSet.of(NodeStatus.RUNNING, NodeStatus.NOT_APPLICABLE),
            NodeStatus.RUNNING, EnumSet.of(NodeStatus.SUCCEEDED, NodeStatus.FAILED, NodeStatus.AWAITING_APPROVAL),
            NodeStatus.AWAITING_APPROVAL, EnumSet.of(NodeStatus.RUNNING, NodeStatus.FAILED, NodeStatus.SAFE_STOPPED),
            NodeStatus.FAILED, EnumSet.of(NodeStatus.RETRY_WAITING, NodeStatus.SAFE_STOPPED),
            NodeStatus.RETRY_WAITING, EnumSet.of(NodeStatus.RUNNING),
            NodeStatus.SAFE_STOPPED, EnumSet.of(NodeStatus.READY),
            NodeStatus.SUCCEEDED, EnumSet.noneOf(NodeStatus.class),
            NodeStatus.NOT_APPLICABLE, EnumSet.noneOf(NodeStatus.class));

    /** Run: plan section 3 "Run" states; COMPLETED and TERMINATED are terminal, SAFE_STOPPED resumes. */
    private static final Map<RunStatus, Set<RunStatus>> LEGAL_RUN = Map.of(
            RunStatus.RUNNING, EnumSet.of(RunStatus.AWAITING_APPROVAL, RunStatus.SUSPENDED_CLARIFICATION, RunStatus.SAFE_STOPPED,
                    RunStatus.COMPLETED, RunStatus.TERMINATED),
            RunStatus.AWAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.SAFE_STOPPED, RunStatus.TERMINATED),
            RunStatus.SUSPENDED_CLARIFICATION, EnumSet.of(RunStatus.RUNNING, RunStatus.SAFE_STOPPED, RunStatus.TERMINATED),
            RunStatus.SAFE_STOPPED, EnumSet.of(RunStatus.RUNNING, RunStatus.TERMINATED),
            RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class),
            RunStatus.TERMINATED, EnumSet.noneOf(RunStatus.class));

    @Test
    void happyPathIsLegal() {
        StateMachines.checkNode(NodeStatus.PENDING, NodeStatus.READY);
        StateMachines.checkNode(NodeStatus.READY, NodeStatus.RUNNING);
        StateMachines.checkNode(NodeStatus.RUNNING, NodeStatus.SUCCEEDED);
    }

    @Test
    void prohibitedTransitionsNamedInTheDataModelAreRejected() {
        assertRejected(NodeStatus.PENDING, NodeStatus.RUNNING);
        assertRejected(NodeStatus.AWAITING_APPROVAL, NodeStatus.SUCCEEDED);
        for (NodeStatus to : NodeStatus.values()) {
            assertRejected(NodeStatus.SUCCEEDED, to);
            assertRejected(NodeStatus.NOT_APPLICABLE, to);
        }
    }

    @Test
    void retryAndApprovalExitsAreLegal() {
        StateMachines.checkNode(NodeStatus.FAILED, NodeStatus.RETRY_WAITING);
        StateMachines.checkNode(NodeStatus.RETRY_WAITING, NodeStatus.RUNNING);
        StateMachines.checkNode(NodeStatus.FAILED, NodeStatus.SAFE_STOPPED);
        StateMachines.checkNode(NodeStatus.AWAITING_APPROVAL, NodeStatus.RUNNING);
        StateMachines.checkNode(NodeStatus.AWAITING_APPROVAL, NodeStatus.FAILED);
        StateMachines.checkNode(NodeStatus.AWAITING_APPROVAL, NodeStatus.SAFE_STOPPED);
    }

    @Test
    void everyNodePairOutsideTheLegalSetIsRejected() {
        for (NodeStatus from : NodeStatus.values()) {
            for (NodeStatus to : NodeStatus.values()) {
                boolean legal = LEGAL_NODE.get(from).contains(to);
                assertThat(StateMachines.isLegalNode(from, to)).as("%s -> %s", from, to).isEqualTo(legal);
            }
        }
        assertThat(NodeStatus.values()).as("every node state is covered").hasSize(LEGAL_NODE.size());
    }

    @Test
    void everyRunPairOutsideTheLegalSetIsRejected() {
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                boolean legal = LEGAL_RUN.get(from).contains(to);
                assertThat(StateMachines.isLegalRun(from, to)).as("%s -> %s", from, to).isEqualTo(legal);
            }
        }
        assertThat(RunStatus.values()).hasSize(LEGAL_RUN.size());
        assertThat(RunStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(RunStatus.TERMINATED.isTerminal()).isTrue();
        assertThat(RunStatus.SAFE_STOPPED.isTerminal()).as("safe-stop is resumable").isFalse();
    }

    private static void assertRejected(NodeStatus from, NodeStatus to) {
        assertThatThrownBy(() -> StateMachines.checkNode(from, to)).as("%s -> %s", from, to)
                .isInstanceOf(IllegalTransitionException.class);
    }
}
