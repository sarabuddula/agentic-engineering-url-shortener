package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticengineering.urlshortener.orchestration.engine.OrchestrationEngine;
import com.agenticengineering.urlshortener.orchestration.persistence.WorkflowStore;
import com.agenticengineering.urlshortener.orchestration.reliability.CompensationService;
import com.agenticengineering.urlshortener.orchestration.reliability.IrreversibleOperationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T077 (FR-027, FR-049; data-model "Rollback vs compensation"; plan section 6 FP-3): code committed at N08 is
 * irreversible, so recovery is compensation: a revert commit, a new forward commit that leaves history intact, recorded
 * in the audit chain and the decision lineage as compensation, not rollback. A rollback attempt on committed code is
 * rejected and audited. The repository is a throwaway fixture under the test's temporary directory.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompensationTest {

    @Autowired
    private CompensationService compensation;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private WorkflowStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path repo;

    private String runId;
    private String base;
    private String implementation;

    @BeforeEach
    void repositoryWithAnN08Commit() throws Exception {
        runId = engine.createRun("api:test-engineer", "POL-2026-10-18-v1");
        git("init", "-q");
        Files.writeString(repo.resolve("LinkStatus.java"), "class LinkStatus {}\n");
        git("add", ".");
        git("commit", "-q", "-m", "base");
        base = git("rev-parse", "HEAD");
        Files.writeString(repo.resolve("LinkStatus.java"), "class LinkStatus { int broken; }\n");
        git("commit", "-q", "-am", "N08: implement task t1");
        implementation = git("rev-parse", "HEAD");
    }

    @Test
    void committedCodeIsCompensatedByARevertCommit() throws Exception {
        CompensationService.Compensation result = compensation.compensateCommit(runId, "N08", repo, implementation,
                "N09 suite failed permanently on task t1");

        assertThat(result.originalCommit()).isEqualTo(implementation);
        assertThat(git("rev-parse", "HEAD")).isEqualTo(result.compensatingCommit()).isNotEqualTo(implementation);
        assertThat(git("rev-parse", "HEAD^")).as("history is kept: the revert is a new forward commit").isEqualTo(implementation);
        assertThat(Files.readString(repo.resolve("LinkStatus.java"))).isEqualTo("class LinkStatus {}\n");
        WorkflowStore.HistoryEntry audited = store.history(runId).stream().filter(e -> e.action().equals("COMPENSATION_APPLIED"))
                .findFirst().orElseThrow();
        assertThat(audited.result()).isEqualTo("FP-3");
        assertThat(audited.reason()).contains(implementation, result.compensatingCommit(), "compensation, not rollback");
        Map<String, Object> lineage = jdbc.queryForMap("SELECT * FROM DECISION_LINEAGE WHERE RUN_ID = ?", runId);
        assertThat((String) lineage.get("DECISION")).contains(result.compensatingCommit());
        assertThat((String) lineage.get("REJECTED_ALTERNATIVES")).contains("rollback");
    }

    @Test
    void aRollbackOfCommittedCodeIsRejectedAndAudited() throws Exception {
        assertThatThrownBy(() -> compensation.rollback(runId, "N08", "commit " + implementation))
                .isInstanceOf(IrreversibleOperationException.class);

        assertThat(git("rev-parse", "HEAD")).isEqualTo(implementation);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("ROLLBACK_REJECTED") && e.reason().contains("irreversible"));
    }

    @Test
    void anUnknownCommitIsNotCompensatedAndTheFailureIsAudited() throws Exception {
        assertThatThrownBy(() -> compensation.compensateCommit(runId, "N08", repo, "0123456789abcdef0123456789abcdef01234567", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compensation.compensateCommit(runId, "N08", repo, "HEAD; rm -rf /", "x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(git("rev-parse", "HEAD")).isEqualTo(implementation);
        assertThat(store.history(runId)).anyMatch(e -> e.action().equals("COMPENSATION_FAILED"));
        assertThat(base).isNotBlank();
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
