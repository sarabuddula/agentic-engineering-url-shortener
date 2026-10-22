package com.agenticengineering.urlshortener.orchestration.api;

import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.Roles;
import com.agenticengineering.urlshortener.orchestration.stages.DesignRevisions;
import com.agenticengineering.urlshortener.shared.api.InvalidRequestException;
import com.agenticengineering.urlshortener.shared.api.ResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /runs/{runId}/design-revisions (T089 option B; contracts/openapi.yaml): ENGINEER records the next DESIGN version
 * with a revision note; 201 with the new version, 409 DESIGN_NOT_FOUND when there is no design.
 */
@RestController
public class DesignRevisionController {

    private final DesignRevisions revisions;
    private final WorkflowStore store;
    private final Roles roles;

    public DesignRevisionController(DesignRevisions revisions, WorkflowStore store, Roles roles) {
        this.revisions = revisions;
        this.store = store;
        this.roles = roles;
    }

    public record DesignRevisionRequest(@JsonProperty("revision_note") String revisionNote) {
    }

    @PostMapping(path = "/api/v1/orchestrator/runs/{runId}/design-revisions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> revise(@PathVariable String runId, @RequestBody DesignRevisionRequest request,
            HttpServletRequest http) throws Exception {
        String keyId = roles.require(http, KeyIdentity.Role.ENGINEER);
        store.runDetail(runId).orElseThrow(() -> new ResourceNotFoundException("RUN_NOT_FOUND", "No such run"));
        if (request.revisionNote() == null || request.revisionNote().isBlank()) {
            throw new InvalidRequestException("revision_note must not be blank");
        }
        ArtifactStore.Artifact revised = revisions.revise(runId, request.revisionNote().strip(), keyId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artifact_type", revised.type());
        body.put("version", revised.version());
        body.put("content_hash", revised.contentHash());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
