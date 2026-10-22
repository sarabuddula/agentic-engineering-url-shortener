package com.agenticengineering.urlshortener.orchestration.engine;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Legal node and run transitions (T053 part 1; data-model.md, plan section 3; FR-020, FR-021, FR-025, SC-004).
 * Anything not listed is prohibited, including PENDING -> RUNNING (entry criteria unchecked), AWAITING_APPROVAL ->
 * SUCCEEDED (no recorded approval), any exit from a terminal state, and NOT_APPLICABLE -> active (node rows are bound to
 * one run revision; a new revision gets new rows). Framework-free.
 */
public final class StateMachines {

    private static final Map<NodeStatus, Set<NodeStatus>> NODE = new EnumMap<>(NodeStatus.class);
    private static final Map<RunStatus, Set<RunStatus>> RUN = new EnumMap<>(RunStatus.class);

    static {
        NODE.put(NodeStatus.PENDING, EnumSet.of(NodeStatus.READY, NodeStatus.NOT_APPLICABLE));
        NODE.put(NodeStatus.READY, EnumSet.of(NodeStatus.RUNNING, NodeStatus.NOT_APPLICABLE));
        NODE.put(NodeStatus.RUNNING, EnumSet.of(NodeStatus.SUCCEEDED, NodeStatus.FAILED, NodeStatus.AWAITING_APPROVAL));
        // APPROVED recorded -> RUNNING (executor finalizes); REJECTED -> FAILED (non-retryable); second timeout -> SAFE_STOPPED
        NODE.put(NodeStatus.AWAITING_APPROVAL, EnumSet.of(NodeStatus.RUNNING, NodeStatus.FAILED, NodeStatus.SAFE_STOPPED));
        // transient -> retry (or pre-approved fallback, same arrow, recorded cause); exhaustion -> declared failure behaviour
        NODE.put(NodeStatus.FAILED, EnumSet.of(NodeStatus.RETRY_WAITING, NodeStatus.SAFE_STOPPED));
        NODE.put(NodeStatus.RETRY_WAITING, EnumSet.of(NodeStatus.RUNNING));
        // governed resumption re-checks entry criteria, so it returns to READY, never straight to RUNNING
        NODE.put(NodeStatus.SAFE_STOPPED, EnumSet.of(NodeStatus.READY));
        NODE.put(NodeStatus.SUCCEEDED, EnumSet.noneOf(NodeStatus.class));
        NODE.put(NodeStatus.NOT_APPLICABLE, EnumSet.noneOf(NodeStatus.class));

        RUN.put(RunStatus.RUNNING, EnumSet.of(RunStatus.AWAITING_APPROVAL, RunStatus.SUSPENDED_CLARIFICATION,
                RunStatus.SAFE_STOPPED, RunStatus.COMPLETED, RunStatus.TERMINATED));
        RUN.put(RunStatus.AWAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.SAFE_STOPPED, RunStatus.TERMINATED));
        RUN.put(RunStatus.SUSPENDED_CLARIFICATION, EnumSet.of(RunStatus.RUNNING, RunStatus.SAFE_STOPPED, RunStatus.TERMINATED));
        RUN.put(RunStatus.SAFE_STOPPED, EnumSet.of(RunStatus.RUNNING, RunStatus.TERMINATED));
        RUN.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
        RUN.put(RunStatus.TERMINATED, EnumSet.noneOf(RunStatus.class));
    }

    private StateMachines() {
    }

    public static boolean isLegalNode(NodeStatus from, NodeStatus to) {
        return NODE.get(from).contains(to);
    }

    public static boolean isLegalRun(RunStatus from, RunStatus to) {
        return RUN.get(from).contains(to);
    }

    public static void checkNode(NodeStatus from, NodeStatus to) {
        if (!isLegalNode(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    public static void checkRun(RunStatus from, RunStatus to) {
        if (!isLegalRun(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }
}
