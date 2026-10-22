package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.engine.TaskSpec;
import com.agenticengineering.urlshortener.orchestration.persistence.TaskStore;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.reliability.CompensationService;
import com.agenticengineering.urlshortener.orchestration.reliability.ReversibleOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T076 (FR-027, FR-049; data-model "Rollback vs compensation"): the one demonstrable rollback - uncommitted rows of a
 * deterministic transform (N05 decomposition) - rolls back cleanly, is verified (no rows remain) and audited
 * ROLLBACK_APPLIED; a rollback requested for an irreversible operation (committed code) is rejected and audited, and the
 * compensating action (a revert commit) is applied instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RollbackTest {

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private ReversibleOperations reversible;

    @Autowired
    private CompensationService compensation;

    @Autowired
    private TaskStore tasks;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path repo;

    @Test
    void uncommittedDecompositionRowsRollBackCleanly() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        ReversibleOperations.Outcome outcome = reversible.run(runId, "N05", "decomposition",
                () -> jdbc.queryForObject("SELECT COUNT(*) FROM TASK WHERE RUN_ID = ?", Integer.class, runId), () -> {
                    tasks.saveDecomposition(runId, List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of()),
                            new TaskSpec("t2", "test", "FIXTURE", List.of("t1"))));
                    throw new IllegalStateException("decomposition rejected by validation after writing");
                });

        assertThat(outcome.rolledBack()).isTrue();
        assertThat(outcome.remainingRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TASK WHERE RUN_ID = ?", Integer.class, runId)).isZero();
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("ROLLBACK_APPLIED") && e.reason().contains("0 rows remain"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FAILURE_EVENT WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void aSuccessfulReversibleOperationCommits() {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");

        ReversibleOperations.Outcome outcome = reversible.run(runId, "N05", "decomposition",
                () -> jdbc.queryForObject("SELECT COUNT(*) FROM TASK WHERE RUN_ID = ?", Integer.class, runId),
                () -> tasks.saveDecomposition(runId, List.of(new TaskSpec("t1", "implement", "FIXTURE", List.of()))));

        assertThat(outcome.rolledBack()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM TASK WHERE RUN_ID = ?", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void aRollbackOfCommittedCodeIsRejectedAndCompensated() throws Exception {
        String runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        git("init", "-q");
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        git("add", ".");
        git("commit", "-q", "-m", "base");
        Files.writeString(repo.resolve("A.java"), "class A { int x; }\n");
        git("commit", "-q", "-am", "N08: task t1");
        String implementation = git("rev-parse", "HEAD");

        CompensationService.Compensation applied = compensation.rollbackOrCompensate(runId, "N08", repo, implementation,
                "N09 suite failed");

        assertThat(store.history(runId)).extracting(WorkflowStore.HistoryEntry::action).containsSubsequence("ROLLBACK_REJECTED",
                "COMPENSATION_APPLIED");
        assertThat(git("rev-parse", "HEAD")).isEqualTo(applied.compensatingCommit());
        assertThat(git("rev-parse", "HEAD^")).isEqualTo(implementation);
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString(), "-c", "user.name=fixture", "-c",
                "user.email=fixture@localhost", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
