package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.ClockConfiguration;
import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.ChainVerification;
import com.agenticengineering.urlshortener.orchestration.engine.EdgeKind;
import com.agenticengineering.urlshortener.orchestration.engine.GraphScheduler;
import com.agenticengineering.urlshortener.orchestration.engine.IllegalTransitionException;
import com.agenticengineering.urlshortener.orchestration.engine.NodeStatus;
import com.agenticengineering.urlshortener.orchestration.engine.RunStatus;
import com.agenticengineering.urlshortener.orchestration.engine.StageGraph;
import com.agenticengineering.urlshortener.orchestration.engine.StageResult;
import com.agenticengineering.urlshortener.orchestration.persistence.StaleStateException;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T053 part 2: persisted node and run lifecycle (FR-022, FR-025, FR-030, PVT-007). Each transition is checked, applied
 * with an optimistic lock and audited in one transaction; illegal attempts are blocked and audited (SC-004); the
 * history returns every transition with actor, action, time, affected item, result and reason (Constitution IX).
 */
@JdbcTest
@Import({WorkflowStore.class, AuditChain.class, ClockConfiguration.class})
class WorkflowLifecycleTest {

    private static final StageGraph GRAPH = StageGraph.builder().node("N01").node("N02").node("N03")
            .edge("N01", "N02", EdgeKind.DEPENDENCY).edge("N02", "N03", EdgeKind.DEPENDENCY).build();

    @Autowired
    private WorkflowStore store;

    @Autowired
    private AuditChain chain;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void creatingARunPersistsItWithItsGraphAndAudits() {
        store.createRun("run-life-1", "req-1", "POL-2026-10-18-v1", GRAPH);

        assertThat(store.runStatus("run-life-1")).isEqualTo(RunStatus.RUNNING);
        assertThat(store.nodeStatuses("run-life-1")).containsOnly(Map.entry("N01", NodeStatus.PENDING),
                Map.entry("N02", NodeStatus.PENDING), Map.entry("N03", NodeStatus.PENDING));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM STAGE_EDGE WHERE RUN_ID = 'run-life-1'", Integer.class)).isEqualTo(2);
        assertThat(store.history("run-life-1")).extracting(WorkflowStore.HistoryEntry::action).containsExactly("RUN_CREATED");
    }

    @Test
    void nodeTransitionsArePersistedWithAttemptCountAndAudited() {
        store.createRun("run-life-2", "req-1", "POL-2026-10-18-v1", GRAPH);

        store.transitionNode("run-life-2", "N01", NodeStatus.PENDING, NodeStatus.READY, null);
        store.transitionNode("run-life-2", "N01", NodeStatus.READY, NodeStatus.RUNNING, null);
        store.transitionNode("run-life-2", "N01", NodeStatus.RUNNING, NodeStatus.SUCCEEDED, null);

        Map<String, Object> row = jdbc.queryForMap("SELECT STATUS, ATTEMPT_COUNT, VERSION, RUN_REVISION FROM STAGE_NODE"
                + " WHERE RUN_ID = 'run-life-2' AND NODE_KEY = 'N01'");
        assertThat(row).containsEntry("STATUS", "SUCCEEDED").containsEntry("ATTEMPT_COUNT", 1).containsEntry("VERSION", 3L)
                .containsEntry("RUN_REVISION", 0);
        assertThat(store.history("run-life-2")).extracting(WorkflowStore.HistoryEntry::result)
                .containsExactly("CREATED", "PENDING->READY", "READY->RUNNING", "RUNNING->SUCCEEDED");
    }

    @Test
    void illegalTransitionsAreBlockedAuditedAndChangeNothing() {
        store.createRun("run-life-3", "req-1", "POL-2026-10-18-v1", GRAPH);

        assertThatThrownBy(() -> store.transitionNode("run-life-3", "N01", NodeStatus.PENDING, NodeStatus.RUNNING, null))
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(store.nodeStatuses("run-life-3")).containsEntry("N01", NodeStatus.PENDING);
        WorkflowStore.HistoryEntry blocked = store.history("run-life-3").get(1);
        assertThat(blocked.action()).isEqualTo("TRANSITION_BLOCKED");
        assertThat(blocked.affected()).isEqualTo("N01");
        assertThat(blocked.result()).isEqualTo("PENDING->RUNNING");
    }

    @Test
    void aStaleExpectedStateIsRejectedWithoutAnAuditEvent() {
        store.createRun("run-life-4", "req-1", "POL-2026-10-18-v1", GRAPH);
        store.transitionNode("run-life-4", "N01", NodeStatus.PENDING, NodeStatus.READY, null);

        assertThatThrownBy(() -> store.transitionNode("run-life-4", "N01", NodeStatus.PENDING, NodeStatus.READY, null))
                .isInstanceOf(StaleStateException.class);
        assertThat(store.history("run-life-4")).hasSize(2);
    }

    @Test
    void runTransitionsAreCheckedAndAudited() {
        store.createRun("run-life-5", "req-1", "POL-2026-10-18-v1", GRAPH);

        store.transitionRun("run-life-5", RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL, "G2 pending");
        store.transitionRun("run-life-5", RunStatus.AWAITING_APPROVAL, RunStatus.RUNNING, "G2 approved");
        store.transitionRun("run-life-5", RunStatus.RUNNING, RunStatus.COMPLETED, "all nodes succeeded");
        assertThatThrownBy(() -> store.transitionRun("run-life-5", RunStatus.COMPLETED, RunStatus.RUNNING, "reopen"))
                .isInstanceOf(IllegalTransitionException.class);

        assertThat(store.runStatus("run-life-5")).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.history("run-life-5")).extracting(WorkflowStore.HistoryEntry::action)
                .containsExactly("RUN_CREATED", "RUN_TRANSITION", "RUN_TRANSITION", "RUN_TRANSITION", "TRANSITION_BLOCKED");
    }

    @Test
    void everyTransitionOfAScheduledRunIsInTheHistoryWithTheFullFieldSet() {
        store.createRun("run-life-6", "req-1", "POL-2026-10-18-v1", GRAPH);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            GraphScheduler scheduler = new GraphScheduler(pool,
                    (node, from, to, reason) -> store.transitionNode("run-life-6", node, from, to, reason));
            scheduler.execute(GRAPH, Map.of("N01", ctx -> StageResult.succeeded(), "N02", ctx -> StageResult.succeeded(),
                    "N03", ctx -> StageResult.succeeded()));
        } finally {
            pool.shutdownNow();
        }

        List<WorkflowStore.HistoryEntry> history = store.history("run-life-6");
        assertThat(history.stream().filter(h -> h.action().equals("NODE_TRANSITION"))).hasSize(9); // 3 nodes x 3 transitions
        assertThat(history).allSatisfy(h -> {
            assertThat(h.actorType()).isNotBlank();
            assertThat(h.action()).isNotBlank();
            assertThat(h.occurredAt()).isNotNull();
            assertThat(h.affected()).isNotBlank();
            assertThat(h.result()).isNotBlank();
            assertThat(h.reason()).isNotBlank();
        });
        assertThat(chain.verify("run-life-6")).isEqualTo(ChainVerification.intact(history.size()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aTransitionAndItsAuditEventRollBackTogether() {
        store.createRun("run-life-7", "req-1", "POL-2026-10-18-v1", GRAPH);
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                store.transitionNode("run-life-7", "N01", NodeStatus.PENDING, NodeStatus.READY, null);
                throw new IllegalStateException("caller fails afterwards");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(store.nodeStatuses("run-life-7")).containsEntry("N01", NodeStatus.PENDING);
            assertThat(store.history("run-life-7")).hasSize(1);
        } finally {
            for (String table : new String[] {"AUDIT_EVENT", "STAGE_EDGE", "STAGE_NODE"}) {
                jdbc.update("DELETE FROM " + table + " WHERE RUN_ID = 'run-life-7'");
            }
            jdbc.update("DELETE FROM WORKFLOW_RUN WHERE ID = 'run-life-7'");
        }
    }
}
