package com.agenticengineering.urlshortener.orchestration.persistence;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.orchestration.engine.EdgeKind;
import com.agenticengineering.urlshortener.orchestration.engine.SdlcWorkflow;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * TASK and TASK_DEPENDENCY rows (T054, FR-036): dependencies are explicit rows, never a list column. The task key is
 * stored as the prefix of TITLE ("key: title") because the data model's TASK has no separate key column; adding one
 * would be a schema change. Tasks belong to the implementation node (N08) of the run's current revision.
 */
@Component
public class TaskStore {

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;

    public TaskStore(JdbcTemplate jdbc, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Persists the decomposition atomically; the dependency graph must be acyclic and reference only its own keys. */
    public void saveDecomposition(String runId, List<TaskSpec> specs) {
        graphOf(specs); // validates keys and acyclicity before anything is written
        tx.executeWithoutResult(status -> {
            long n08 = jdbc.queryForObject("SELECT n.ID FROM STAGE_NODE n JOIN WORKFLOW_RUN r ON r.ID = n.RUN_ID AND"
                    + " r.RUN_REVISION = n.RUN_REVISION WHERE n.RUN_ID = ? AND n.NODE_KEY = ?", Long.class, runId,
                    SdlcWorkflow.IMPLEMENTATION_NODE);
            Map<String, Long> ids = new HashMap<>();
            for (TaskSpec spec : specs) {
                jdbc.update("INSERT INTO TASK (RUN_ID, NODE_ID, TITLE, REQUIREMENT_REFS, STATUS, EXECUTOR_CLASS)"
                        + " VALUES (?, ?, ?, ?, 'PENDING', 'DETERMINISTIC')", runId, n08, spec.key() + ": " + spec.title(),
                        spec.requirementRefs());
                ids.put(spec.key(), jdbc.queryForObject("SELECT MAX(ID) FROM TASK WHERE RUN_ID = ?", Long.class, runId));
            }
            for (TaskSpec spec : specs) {
                for (String dependency : spec.dependsOn()) {
                    jdbc.update("INSERT INTO TASK_DEPENDENCY (TASK_ID, DEPENDS_ON_TASK_ID) VALUES (?, ?)", ids.get(spec.key()),
                            ids.get(dependency));
                }
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.AGENT, "DECOMPOSITION_RECORDED", clock.now(),
                    SdlcWorkflow.IMPLEMENTATION_NODE, specs.size() + " tasks",
                    "tasks and explicit dependency rows persisted for N08"));
        });
    }

    /** The task graph of the run: task keys as nodes, TASK_DEPENDENCY rows as DEPENDENCY edges. */
    public StageGraph taskGraph(String runId) {
        StageGraph.Builder builder = StageGraph.builder();
        Map<Long, String> keys = new HashMap<>();
        jdbc.query("SELECT ID, TITLE FROM TASK WHERE RUN_ID = ? ORDER BY ID", rs -> {
            String key = keyOf(rs.getString("TITLE"));
            keys.put(rs.getLong("ID"), key);
            builder.node(key);
        }, runId);
        jdbc.query("SELECT d.TASK_ID, d.DEPENDS_ON_TASK_ID FROM TASK_DEPENDENCY d JOIN TASK t ON t.ID = d.TASK_ID WHERE t.RUN_ID = ?",
                rs -> {
                    builder.edge(keys.get(rs.getLong("DEPENDS_ON_TASK_ID")), keys.get(rs.getLong("TASK_ID")), EdgeKind.DEPENDENCY);
                }, runId);
        return builder.build();
    }

    public void transitionTask(String runId, String key, String from, String to, String reason) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE TASK SET STATUS = ? WHERE RUN_ID = ? AND TITLE LIKE ? AND STATUS = ?", to, runId,
                    key + ": %", from);
            if (updated != 1) {
                throw new StaleStateException("task " + key + " is not " + from);
            }
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "TASK_TRANSITION", clock.now(), key, from + "->" + to,
                    reason != null ? reason : "task state change"));
        });
    }

    public Map<String, String> statuses(String runId) {
        Map<String, String> statuses = new LinkedHashMap<>();
        jdbc.query("SELECT TITLE, STATUS FROM TASK WHERE RUN_ID = ? ORDER BY ID",
                rs -> {
                    statuses.put(keyOf(rs.getString("TITLE")), rs.getString("STATUS"));
                }, runId);
        return statuses;
    }

    private static String keyOf(String title) {
        return title.substring(0, title.indexOf(':'));
    }

    private static StageGraph graphOf(List<TaskSpec> specs) {
        StageGraph.Builder builder = StageGraph.builder();
        specs.forEach(s -> builder.node(s.key()));
        specs.forEach(s -> s.dependsOn().forEach(d -> builder.edge(d, s.key(), EdgeKind.DEPENDENCY)));
        return builder.build();
    }
}
