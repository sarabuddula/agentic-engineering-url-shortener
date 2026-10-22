package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.persistence.RequirementStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import org.springframework.stereotype.Component;

/**
 * N01 requirement ingestion (plan section 3): normalizes the raw text and stores it as REQUIREMENT_ARTIFACT version 1,
 * through the audited N01 transitions. Blank input is rejected before a run exists (defined rejection).
 */
@Component
public class RequirementIngestion {

    private final WorkflowStore store;
    private final RequirementStore requirements;

    public RequirementIngestion(WorkflowStore store, RequirementStore requirements) {
        this.store = store;
        this.requirements = requirements;
    }

    /** @return the normalized text; throws IllegalArgumentException for blank input */
    public static String normalize(String raw) {
        String text = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("requirement_text is empty");
        }
        return text;
    }

    public void ingest(String runId, String normalizedText) {
        store.transitionNode(runId, "N01", NodeStatus.PENDING, NodeStatus.READY, "run created");
        store.transitionNode(runId, "N01", NodeStatus.READY, NodeStatus.RUNNING, "ingestion dispatched");
        requirements.saveIngested(runId, normalizedText, RequirementStore.Provenance.PROPOSED);
        store.transitionNode(runId, "N01", NodeStatus.RUNNING, NodeStatus.SUCCEEDED, "requirement artifact version 1 stored");
    }
}
