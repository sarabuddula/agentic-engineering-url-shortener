package com.agenticengineering.urlshortener.orchestration.reliability;

import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * OPERATION_RECORD (T122; PVT-006): an effectful operation is recorded PENDING before its effect and COMPLETED after,
 * so restart reconciliation can tell "never applied" from "applied but not recorded". Operation ids encode what
 * EffectVerifier needs to find the effect.
 */
@Component
public class OperationLog {

    private final JdbcTemplate jdbc;
    private final WorkflowStore store;

    public OperationLog(JdbcTemplate jdbc, WorkflowStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** ARTIFACT_WRITE id: "aw:" + 16 hex of the run id's hash + ":" + the first 40 hex of the content hash. */
    public static String artifactWriteId(String runId, String contentHash) {
        return "aw:" + com.agenticengineering.urlshortener.orchestration.persistence.ArtifactStore.sha256(runId).substring(0, 16) + ":"
                + contentHash.substring(0, 40);
    }

    public String begin(String runId, String node, String type, String operationId) {
        jdbc.update("MERGE INTO OPERATION_RECORD (OPERATION_ID, RUN_ID, NODE_ID, TYPE, STATUS, EFFECT_VERIFIED, CREATED_AT) KEY (OPERATION_ID)"
                + " VALUES (?, ?, ?, ?, 'PENDING', FALSE, ?)", operationId, runId, store.nodeId(runId, node), type,
                Timestamp.from(Instant.now()));
        return operationId;
    }

    public void complete(String operationId, boolean effectVerified) {
        jdbc.update("UPDATE OPERATION_RECORD SET STATUS = 'COMPLETED', EFFECT_VERIFIED = ?, COMPLETED_AT = ? WHERE OPERATION_ID = ?",
                effectVerified, Timestamp.from(Instant.now()), operationId);
    }
}
