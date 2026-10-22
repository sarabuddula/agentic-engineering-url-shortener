package com.agenticengineering.urlshortener.orchestration.persistence;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versioned run artifacts (data-model ARTIFACT). The row keeps type, version, provenance and the SHA-256 content hash;
 * the content is stored content-addressed under {app.data-dir}/artifacts/{hash} (the ARTIFACT table has no content
 * column). Reads re-hash the content, so a changed file is reported instead of being served.
 */
@Component
public class ArtifactStore {

    public record Artifact(long id, String runId, String type, int version, String contentHash, String provenance, Instant createdAt,
            String content) {
    }

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;
    private final Path directory;

    public ArtifactStore(JdbcTemplate jdbc, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions,
            @Value("${app.data-dir}") Path dataDir) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.directory = dataDir.resolve("artifacts");
    }

    /** Stores the content, adds the next version of the type for the run, and audits it against the producing node. */
    public Artifact record(String runId, String type, String content, String provenance, String node) {
        String hash = sha256(content);
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(hash);
            if (!Files.exists(file)) {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tx.execute(status -> {
            Instant now = clock.now();
            int version = jdbc.queryForObject("SELECT COALESCE(MAX(VERSION), 0) + 1 FROM ARTIFACT WHERE RUN_ID = ? AND TYPE = ?",
                    Integer.class, runId, type);
            jdbc.update("INSERT INTO ARTIFACT (RUN_ID, TYPE, VERSION, CONTENT_HASH, PROVENANCE, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
                    runId, type, version, hash, provenance, Timestamp.from(now));
            audit.append(runId, new AuditEntry(AuditEntry.ActorType.AGENT, "ARTIFACT_RECORDED", now, node, "RECORDED",
                    type + " v" + version + " (" + provenance + ") sha256 " + hash));
            return latest(runId, type).orElseThrow();
        });
    }

    /**
     * Returns the latest version of the type and records that the node (in the run's current revision) read it, with the
     * content hash at read (NODE_INPUT, role INPUT): what a replan re-validates against.
     */
    public Artifact read(String runId, String type, String node) {
        Artifact artifact = latest(runId, type).orElseThrow(() -> new IllegalStateException("no " + type + " artifact in run " + runId));
        jdbc.update("MERGE INTO NODE_INPUT (NODE_ID, ARTIFACT_ID, ARTIFACT_VERSION, CONTENT_HASH_AT_READ, ROLE, RECORDED_AT)"
                + " KEY (NODE_ID, ARTIFACT_ID, ARTIFACT_VERSION) SELECT n.ID, ?, ?, ?, 'INPUT', ? FROM STAGE_NODE n JOIN WORKFLOW_RUN r"
                + " ON r.ID = n.RUN_ID AND r.RUN_REVISION = n.RUN_REVISION WHERE n.RUN_ID = ? AND n.NODE_KEY = ?", artifact.id(),
                artifact.version(), artifact.contentHash(), Timestamp.from(clock.now()), runId, node);
        return artifact;
    }

    public Optional<Artifact> latest(String runId, String type) {
        List<Artifact> rows = jdbc.query("SELECT * FROM ARTIFACT WHERE RUN_ID = ? AND TYPE = ? ORDER BY VERSION DESC LIMIT 1",
                (rs, i) -> new Artifact(rs.getLong("ID"), rs.getString("RUN_ID"), rs.getString("TYPE"), rs.getInt("VERSION"),
                        rs.getString("CONTENT_HASH"), rs.getString("PROVENANCE"), rs.getTimestamp("CREATED_AT").toInstant(),
                        content(rs.getString("CONTENT_HASH"))), runId, type);
        return rows.stream().findFirst();
    }

    private String content(String hash) {
        try {
            String content = Files.readString(directory.resolve(hash), StandardCharsets.UTF_8);
            if (!sha256(content).equals(hash)) {
                throw new IllegalStateException("artifact content " + hash + " does not match its recorded hash");
            }
            return content;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
