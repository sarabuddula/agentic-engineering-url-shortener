package com.agenticengineering.urlshortener.orchestration.reliability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Effect verification per operation type (T122; plan section 6). ARTIFACT_WRITE and DOC_GENERATE: the artifact with the
 * content hash encoded in the operation id exists in the run; TEST_RUN: a TEST_OUTCOME row whose evidence ref is
 * "operation:{id}"; POLICY_EVAL: a complete evaluation (one row per check of the 16-check policy set) for the run;
 * APPROVAL_REQUEST: an APPROVAL row for the operation's node. Any other type is UNVERIFIABLE.
 */
@Component
public class EffectVerifier {

    public enum Verdict { APPLIED, NOT_APPLIED, UNVERIFIABLE }

    private final JdbcTemplate jdbc;

    public EffectVerifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Verdict verify(String runId, String type, String operationId, long nodeId) {
        return switch (type) {
            case "ARTIFACT_WRITE", "DOC_GENERATE" -> found("SELECT COUNT(*) FROM ARTIFACT WHERE RUN_ID = ? AND CONTENT_HASH LIKE ?", runId,
                    operationId.substring(operationId.lastIndexOf(':') + 1) + "%");
            case "TEST_RUN" -> found("SELECT COUNT(*) FROM TEST_OUTCOME WHERE RUN_ID = ? AND EVIDENCE_REF = ?", runId, "operation:" + operationId);
            case "POLICY_EVAL" -> jdbc.queryForObject("SELECT COUNT(DISTINCT CHECK_ID) FROM POLICY_EVALUATION WHERE RUN_ID = ?",
                    Integer.class, runId) >= 16 ? Verdict.APPLIED : Verdict.NOT_APPLIED;
            case "APPROVAL_REQUEST" -> found("SELECT COUNT(*) FROM APPROVAL a JOIN STAGE_NODE n ON a.SCOPE = CONCAT('node=', n.NODE_KEY)"
                    + " WHERE a.RUN_ID = ? AND n.ID = ?", runId, nodeId);
            default -> Verdict.UNVERIFIABLE;
        };
    }

    private Verdict found(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args) > 0 ? Verdict.APPLIED : Verdict.NOT_APPLIED;
    }
}
