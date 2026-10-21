package com.agenticengineering.urlshortener.orchestration.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hash-chained, append-only run audit (T021; FR-048, NFR-006, ADR-005, plan section 4). Each event hash covers a
 * canonical serialization of (run id, seq, predecessor hash, event fields). Append locks the run row, inserts the event
 * and advances the run's retained head hash and count in the caller's transaction, so a state change and its audit
 * event commit or roll back together, and parallel nodes cannot create competing chain heads.
 */
@Component
public class AuditChain {

    /** Predecessor hash of the first event in every chain. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private final JdbcTemplate jdbc;

    public AuditChain(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Appended(long seq, String eventHash) {
    }

    @Transactional
    public Appended append(String runId, AuditEntry entry) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT AUDIT_HEAD_HASH, AUDIT_EVENT_COUNT FROM WORKFLOW_RUN WHERE ID = ? FOR UPDATE", runId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("unknown run " + runId);
        }
        String head = (String) rows.get(0).get("AUDIT_HEAD_HASH");
        long seq = ((Number) rows.get(0).get("AUDIT_EVENT_COUNT")).longValue() + 1;
        String prevHash = head == null ? GENESIS_HASH : head;
        // H2 keeps microseconds; hash the value that will be read back.
        Instant occurredAt = entry.occurredAt().truncatedTo(ChronoUnit.MICROS);
        String eventHash = hash(runId, seq, prevHash, entry, occurredAt);

        jdbc.update("INSERT INTO AUDIT_EVENT (RUN_ID, SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, AFFECTED_ARTIFACT, RESULT, REASON,"
                + " PREV_HASH, EVENT_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", runId, seq, entry.actorType().name(),
                entry.action(), Timestamp.from(occurredAt), entry.affectedArtifact(), entry.result(), entry.reason(),
                prevHash, eventHash);
        jdbc.update("UPDATE WORKFLOW_RUN SET AUDIT_HEAD_HASH = ?, AUDIT_EVENT_COUNT = ? WHERE ID = ?", eventHash, seq, runId);
        return new Appended(seq, eventHash);
    }

    @Transactional(readOnly = true)
    public ChainVerification verify(String runId) {
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT AUDIT_HEAD_HASH, AUDIT_EVENT_COUNT FROM WORKFLOW_RUN WHERE ID = ?", runId);
        String retainedHead = (String) run.get("AUDIT_HEAD_HASH");
        long retainedCount = ((Number) run.get("AUDIT_EVENT_COUNT")).longValue();

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT * FROM AUDIT_EVENT WHERE RUN_ID = ? ORDER BY SEQ", runId);
        long expectedSeq = 1;
        String prevHash = GENESIS_HASH;
        for (Map<String, Object> event : events) {
            if (((Number) event.get("SEQ")).longValue() != expectedSeq) {
                return ChainVerification.broken(expectedSeq, ChainVerification.Check.SEQUENCE_GAP);
            }
            if (!prevHash.equals(event.get("PREV_HASH"))) {
                return ChainVerification.broken(expectedSeq, ChainVerification.Check.PREDECESSOR_MISMATCH);
            }
            AuditEntry stored = new AuditEntry(AuditEntry.ActorType.valueOf((String) event.get("ACTOR_TYPE")),
                    (String) event.get("ACTION"), instant(event.get("OCCURRED_AT")), (String) event.get("AFFECTED_ARTIFACT"),
                    (String) event.get("RESULT"), (String) event.get("REASON"));
            String recomputed = hash(runId, expectedSeq, prevHash, stored, stored.occurredAt());
            if (!recomputed.equals(event.get("EVENT_HASH"))) {
                return ChainVerification.broken(expectedSeq, ChainVerification.Check.HASH_MISMATCH);
            }
            prevHash = recomputed;
            expectedSeq++;
        }
        long verified = expectedSeq - 1;
        if (verified < retainedCount) {
            return ChainVerification.broken(verified + 1, ChainVerification.Check.TRUNCATION);
        }
        if (verified > retainedCount || (verified > 0 && !prevHash.equals(retainedHead))) {
            return ChainVerification.broken(verified, ChainVerification.Check.HEAD_MISMATCH);
        }
        return ChainVerification.intact(verified);
    }

    /** Canonical serialization: each field length-prefixed, so no field boundary can be forged by content. */
    static String hash(String runId, long seq, String prevHash, AuditEntry entry, Instant occurredAt) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {runId, Long.toString(seq), prevHash, entry.actorType().name(), entry.action(),
                occurredAt.toString(), entry.affectedArtifact(), entry.result(), entry.reason()}) {
            canonical.append(field == null ? "~" : field.length() + ":" + field).append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("unexpected timestamp type " + (value == null ? "null" : value.getClass()));
    }
}
