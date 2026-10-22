package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.approval.ApprovalKind;
import com.agenticengineering.urlshortener.orchestration.engine.GateExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.agenticengineering.urlshortener.orchestration.stages.DocumentationStageExecutor;
import com.agenticengineering.urlshortener.orchestration.stages.ImplementationTaskExecutor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T089 option B (human decision 2026-10-22, approvals/2026-10-22-t089-design-revisions.md): the production stages that
 * consume the design record what they read (NODE_INPUT), so a later design revision is detected by a replan - N07 (G2
 * gate), N08 (each implementation task) and N10 (documentation). Requirements: FR-029, SC-003.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DesignReadRecordingTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private GateExecutor.Factory gates;

    @Autowired
    private DocumentationStageExecutor.Factory documentation;

    @Autowired
    private ImplementationTaskExecutor.Factory implementation;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theDesignConsumersRecordTheVersionTheyRead() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        ArtifactStore.Artifact design = artifacts.record(runId, "DESIGN", "{\"tasks\":{}}", "DETERMINISTIC", "N06");
        artifacts.record(runId, "CODE", "{\"task\":\"t1\",\"commit\":\"abc\",\"files\":[]}", "AI_ASSISTED_HUMAN_REVIEWED", "N08");

        gates.gate(ApprovalKind.DESIGN_APPROVAL).execute(new RunContext(runId, "N07"));
        implementation.production().execute(runId, "t1");
        documentation.production().execute(new RunContext(runId, "N10"));

        assertThat(designReads(runId)).containsExactlyInAnyOrder("N07@1#" + design.contentHash(), "N08@1#" + design.contentHash(),
                "N10@1#" + design.contentHash());
    }

    @Test
    void aGateWithoutADesignRecordsNothing() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        gates.gate(ApprovalKind.DESIGN_APPROVAL).execute(new RunContext(runId, "N07"));

        assertThat(designReads(runId)).isEmpty();
    }

    private List<String> designReads(String runId) {
        return jdbc.queryForList("SELECT n.NODE_KEY || '@' || i.ARTIFACT_VERSION || '#' || i.CONTENT_HASH_AT_READ FROM NODE_INPUT i"
                + " JOIN STAGE_NODE n ON n.ID = i.NODE_ID JOIN ARTIFACT a ON a.ID = i.ARTIFACT_ID WHERE n.RUN_ID = ? AND a.TYPE = 'DESIGN'",
                String.class, runId);
    }
}
