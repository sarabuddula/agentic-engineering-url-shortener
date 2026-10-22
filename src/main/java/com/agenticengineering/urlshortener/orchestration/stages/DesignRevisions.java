package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Design revisions (T089 option B; human decision 2026-10-22, approvals/2026-10-22-t089-design-revisions.md): an ENGINEER
 * records the next DESIGN version - the previous design plus a revision note - so the stages that read the earlier
 * version become a live replan trigger (FR-029). The note is human-reviewed content (AI_ASSISTED_HUMAN_REVIEWED).
 */
@Component
public class DesignRevisions {

    /** The run has no DESIGN artifact to revise. */
    public static final class DesignNotFoundException extends RuntimeException {
        public DesignNotFoundException(String runId) {
            super("run " + runId + " has no design to revise");
        }
    }

    private final ArtifactStore artifacts;
    private final AuditChain audit;
    private final TimeSource clock;
    private final ObjectMapper json = new ObjectMapper();

    public DesignRevisions(ArtifactStore artifacts, AuditChain audit, TimeSource clock) {
        this.artifacts = artifacts;
        this.audit = audit;
        this.clock = clock;
    }

    public ArtifactStore.Artifact revise(String runId, String note, String keyId) throws Exception {
        ArtifactStore.Artifact previous = artifacts.latest(runId, "DESIGN").orElseThrow(() -> new DesignNotFoundException(runId));
        JsonNode parsed = json.readTree(previous.content());
        ObjectNode design = parsed instanceof ObjectNode o ? o : json.createObjectNode().set("design", parsed);
        ObjectNode revision = design.withArray("revision_notes").addObject();
        revision.put("note", note);
        revision.put("revised_by", keyId);
        revision.put("revised_at", clock.now().toString());
        revision.put("previous_version", previous.version());
        revision.put("previous_sha256", previous.contentHash());
        ArtifactStore.Artifact revised = artifacts.record(runId, "DESIGN", json.writeValueAsString(design), "AI_ASSISTED_HUMAN_REVIEWED", "N06");
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.HUMAN, "DESIGN_REVISED", clock.now(), "N06", "DESIGN v" + revised.version(),
                "design revised by " + keyId + " (v" + previous.version() + " -> v" + revised.version() + "): " + note));
        return revised;
    }
}
