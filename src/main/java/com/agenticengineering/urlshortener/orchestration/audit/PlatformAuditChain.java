package com.agenticengineering.urlshortener.orchestration.audit;

import com.agenticengineering.urlshortener.shared.audit.PlatformAudit;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hash-chained platform audit (PLATFORM_AUDIT_EVENT, PLATFORM_AUDIT_HEAD): the same hashing and verification as the
 * run chain (AuditChain, T021) with its own sequence and a singleton head row as the independent truncation reference.
 */
@Component
public class PlatformAuditChain implements PlatformAudit {

    /** Chain identifier used in the hash input in place of a run id. */
    static final String CHAIN_ID = "platform";

    private final JdbcTemplate jdbc;
    private final TimeSource clock;

    public PlatformAuditChain(JdbcTemplate jdbc, TimeSource clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(String action, String affected, String result, String reason) {
        jdbc.update("MERGE INTO PLATFORM_AUDIT_HEAD (ID, EVENT_COUNT) KEY (ID) SELECT 1, 0 FROM DUAL"
                + " WHERE NOT EXISTS (SELECT 1 FROM PLATFORM_AUDIT_HEAD WHERE ID = 1)");
        Map<String, Object> head = jdbc.queryForMap("SELECT HEAD_HASH, EVENT_COUNT FROM PLATFORM_AUDIT_HEAD WHERE ID = 1 FOR UPDATE");
        long seq = ((Number) head.get("EVENT_COUNT")).longValue() + 1;
        String prevHash = head.get("HEAD_HASH") == null ? AuditChain.GENESIS_HASH : (String) head.get("HEAD_HASH");
        AuditEntry entry = new AuditEntry(AuditEntry.ActorType.SYSTEM, action, clock.now(), affected, result, reason);
        String eventHash = AuditChain.hash(CHAIN_ID, seq, prevHash, entry, entry.occurredAt());
        jdbc.update("INSERT INTO PLATFORM_AUDIT_EVENT (SEQ, ACTOR_TYPE, ACTION, OCCURRED_AT, AFFECTED_ARTIFACT, RESULT, REASON,"
                + " PREV_HASH, EVENT_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", seq, entry.actorType().name(), action,
                Timestamp.from(entry.occurredAt()), affected, result, reason, prevHash, eventHash);
        jdbc.update("UPDATE PLATFORM_AUDIT_HEAD SET HEAD_HASH = ?, EVENT_COUNT = ?, UPDATED_AT = ? WHERE ID = 1", eventHash, seq,
                Timestamp.from(entry.occurredAt()));
    }

    @Transactional(readOnly = true)
    public ChainVerification verify() {
        List<Map<String, Object>> heads = jdbc.queryForList("SELECT HEAD_HASH, EVENT_COUNT FROM PLATFORM_AUDIT_HEAD WHERE ID = 1");
        String retainedHead = heads.isEmpty() ? null : (String) heads.get(0).get("HEAD_HASH");
        long retainedCount = heads.isEmpty() ? 0 : ((Number) heads.get(0).get("EVENT_COUNT")).longValue();
        long expectedSeq = 1;
        String prevHash = AuditChain.GENESIS_HASH;
        for (Map<String, Object> event : jdbc.queryForList("SELECT * FROM PLATFORM_AUDIT_EVENT ORDER BY SEQ")) {
            if (((Number) event.get("SEQ")).longValue() != expectedSeq) {
                return ChainVerification.broken(expectedSeq, ChainVerification.Check.SEQUENCE_GAP);
            }
            if (!prevHash.equals(event.get("PREV_HASH"))) {
                return ChainVerification.broken(expectedSeq, ChainVerification.Check.PREDECESSOR_MISMATCH);
            }
            Object at = event.get("OCCURRED_AT");
            Instant occurredAt = at instanceof Timestamp ts ? ts.toInstant() : ((OffsetDateTime) at).toInstant();
            AuditEntry stored = new AuditEntry(AuditEntry.ActorType.valueOf((String) event.get("ACTOR_TYPE")),
                    (String) event.get("ACTION"), occurredAt, (String) event.get("AFFECTED_ARTIFACT"), (String) event.get("RESULT"),
                    (String) event.get("REASON"));
            String recomputed = AuditChain.hash(CHAIN_ID, expectedSeq, prevHash, stored, occurredAt);
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
}
