package com.agenticengineering.urlshortener.orchestration.stages;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.engine.RunContext;
import com.agenticengineering.urlshortener.orchestration.engine.StageExecutor;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.lineage.DecisionLineage;
import com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * N11 evidence assembly (plan section 3; whitelist AR, AW, MQ): an EVIDENCE artifact with the run's real test counts
 * (from N09's TEST_OUTCOME rows), the current artifact versions and the chain state, for N12 and the reviewer. Without
 * executed tests there is no evidence and the stage fails.
 */
@Component
public class EvidenceStageExecutor implements StageExecutor {

    private final JdbcTemplate jdbc;
    private final ArtifactStore artifacts;
    private final DecisionLineage lineage;
    private final AuditChain chain;
    private final ObjectMapper json = new ObjectMapper();

    public EvidenceStageExecutor(JdbcTemplate jdbc, ArtifactStore artifacts, DecisionLineage lineage, AuditChain chain) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.lineage = lineage;
        this.chain = chain;
    }

    @Override
    public StageResult execute(RunContext context) throws Exception {
        String runId = context.runId();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : new String[] {"PASSED", "FAILED", "SKIPPED"}) {
            counts.put(status.toLowerCase(), jdbc.queryForObject("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND STATUS = ?",
                    Integer.class, runId, status));
        }
        if (counts.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            return StageResult.failed("permanent: no test outcomes recorded by N09; evidence cannot be assembled");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("tests", counts);
        evidence.put("artifacts", lineage.currentArtifacts(runId));
        evidence.put("chain_intact_at_assembly", chain.verify(runId).intact());
        artifacts.record(runId, "EVIDENCE", json.writeValueAsString(evidence), "DETERMINISTIC", "N11");
        return counts.get("failed") > 0 ? StageResult.failed("permanent: " + counts.get("failed") + " test(s) failed") : StageResult.succeeded();
    }
}
