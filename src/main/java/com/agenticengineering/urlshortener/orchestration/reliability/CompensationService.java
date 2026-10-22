package com.agenticengineering.urlshortener.orchestration.reliability;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FP-3 compensation (T077; FR-027, FR-049; data-model "Rollback vs compensation"). Committed code is irreversible: a
 * git revert is a new forward commit, so it is compensation by definition and never rewrites history. Each compensation
 * is audited and recorded in DECISION_LINEAGE with rollback as the rejected alternative; rollback requests for
 * irreversible operations are rejected and audited.
 */
@Component
public class CompensationService {

    public record Compensation(String originalCommit, String compensatingCommit) {
    }

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

    private final AuditChain audit;
    private final JdbcTemplate jdbc;
    private final TimeSource clock;

    public CompensationService(AuditChain audit, JdbcTemplate jdbc, TimeSource clock) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Compensation compensateCommit(String runId, String node, Path repository, String commit, String cause) {
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            failed(runId, node, "malformed commit id refused");
            throw new IllegalArgumentException("not a commit id: " + commit);
        }
        try {
            if (git(repository, "cat-file", "-e", commit + "^{commit}").exitCode() != 0) {
                failed(runId, node, "commit " + commit + " does not exist in " + repository.getFileName());
                throw new IllegalArgumentException("no commit " + commit);
            }
            Result revert = git(repository, "revert", "--no-edit", commit);
            if (revert.exitCode() != 0) {
                git(repository, "revert", "--abort");
                failed(runId, node, "git revert of " + commit + " failed: " + revert.output());
                throw new IllegalStateException("revert of " + commit + " failed");
            }
            String compensating = git(repository, "rev-parse", "HEAD").output();
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "COMPENSATION_APPLIED", clock.now(), node, "FP-3",
                    "commit " + commit + " compensated by revert commit " + compensating + " after: " + cause
                            + " (compensation, not rollback: committed code is irreversible)"));
            jdbc.update("INSERT INTO DECISION_LINEAGE (RUN_ID, DECISION, RATIONALE, REJECTED_ALTERNATIVES, ARTIFACT_VERSION_REFS)"
                    + " VALUES (?, ?, ?, ?, ?)", runId, "compensate " + node + " commit " + commit + " with revert commit " + compensating,
                    "FP-3 after: " + cause + "; a revert is a new forward commit, so the original stays in history",
                    "rollback (rewriting or resetting history): rejected, committed code is irreversible (FR-027, FR-049)",
                    "commit:" + commit + ", commit:" + compensating);
            return new Compensation(commit, compensating);
        } catch (IOException e) {
            failed(runId, node, "git unavailable: " + e.getMessage());
            throw new IllegalStateException("git unavailable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Rollback is only for reversible operations (uncommitted transaction rows); any other request is refused. */
    public void rollback(String runId, String node, String operation) {
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "ROLLBACK_REJECTED", clock.now(), node, "REJECTED",
                "rollback of " + operation + " refused: the operation is irreversible; compensation applies (FR-049)"));
        throw new IrreversibleOperationException(operation + " is irreversible; use compensation");
    }

    private void failed(String runId, String node, String reason) {
        audit.append(runId, new AuditEntry(AuditEntry.ActorType.SYSTEM, "COMPENSATION_FAILED", clock.now(), node, "FAILED", reason));
    }

    private record Result(int exitCode, String output) {
    }

    private static Result git(Path repository, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString(), "-c", "user.name=orchestrator", "-c",
                "user.email=orchestrator@localhost", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new Result(process.waitFor(), output);
    }
}
