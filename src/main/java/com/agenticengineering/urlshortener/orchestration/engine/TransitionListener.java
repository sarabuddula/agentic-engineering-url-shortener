package com.agenticengineering.urlshortener.orchestration.engine;

/** Receives every node transition, in order, on the scheduler thread (persistence and audit hook, T053). */
@FunctionalInterface
public interface TransitionListener {

    void onTransition(String node, NodeStatus from, NodeStatus to, String reason);
}
