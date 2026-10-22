package com.agenticengineering.urlshortener.orchestration.lineage;

import com.agenticengineering.urlshortener.orchestration.audit.AuditChain;
import com.agenticengineering.urlshortener.orchestration.audit.AuditEntry;
import com.agenticengineering.urlshortener.shared.port.TimeSource;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DECISION_LINEAGE writer (T118; FR-023, FR-029): each recorded decision with its rationale, the alternatives it
 * rejected and the artifact versions it concerns (id@version#content_hash), plus an audit event DECISION_RECORDED whose
 * affected reference is "decision:{id}", so the history can attach the rationale to the chain entry.
 */
@Component
public class DecisionLineage {

    public record Decision(long id, String decision, String rationale, List<String> rejectedAlternatives, List<String> artifactRefs) {
    }

    private final JdbcTemplate jdbc;
    private final AuditChain audit;
    private final TimeSource clock;
    private final TransactionTemplate tx;

    public DecisionLineage(JdbcTemplate jdbc, AuditChain audit, TimeSource clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Records the decision; {@code kind} (for example DESIGN_APPROVAL, REPLAN, COMPENSATION) is the audit result. */
    public long record(String runId, String kind, AuditEntry.ActorType actor, String decision, String rationale,
            List<String> rejectedAlternatives, List<String> artifactRefs) {
        return tx.execute(status -> {
            jdbc.update("INSERT INTO DECISION_LINEAGE (RUN_ID, DECISION, RATIONALE, REJECTED_ALTERNATIVES, ARTIFACT_VERSION_REFS)"
                    + " VALUES (?, ?, ?, ?, ?)", runId, bounded(decision, 1024), bounded(rationale, 2048),
                    bounded(String.join("\n", rejectedAlternatives), 2048), bounded(String.join("\n", artifactRefs), 2048));
            long id = jdbc.queryForObject("SELECT MAX(ID) FROM DECISION_LINEAGE WHERE RUN_ID = ?", Long.class, runId);
            audit.append(runId, new AuditEntry(actor, "DECISION_RECORDED", clock.now(), "decision:" + id, bounded(kind, 32),
                    bounded(decision, 900)));
            return id;
        });
    }

    /** The latest version of every artifact the run has, as id@version#content_hash. */
    public List<String> currentArtifacts(String runId) {
        return jdbc.query("SELECT a.ID, a.VERSION, a.CONTENT_HASH FROM ARTIFACT a WHERE a.RUN_ID = ? AND a.VERSION ="
                + " (SELECT MAX(b.VERSION) FROM ARTIFACT b WHERE b.RUN_ID = a.RUN_ID AND b.TYPE = a.TYPE) ORDER BY a.TYPE",
                (rs, i) -> rs.getLong("ID") + "@" + rs.getInt("VERSION") + "#" + rs.getString("CONTENT_HASH"), runId);
    }

    public List<Decision> forRun(String runId) {
        return jdbc.query("SELECT * FROM DECISION_LINEAGE WHERE RUN_ID = ? ORDER BY ID", (rs, i) -> new Decision(rs.getLong("ID"),
                rs.getString("DECISION"), rs.getString("RATIONALE"), lines(rs.getString("REJECTED_ALTERNATIVES")),
                lines(rs.getString("ARTIFACT_VERSION_REFS"))), runId);
    }

    /** The evidence class (HUMAN or SIMULATED) of the approval behind a decision (T127). */
    public String evidenceClassOf(String runId, long approvalId) {
        return jdbc.queryForObject("SELECT EVIDENCE_CLASS FROM APPROVAL WHERE RUN_ID = ? AND ID = ?", String.class, runId, approvalId);
    }

    public java.util.Optional<Decision> find(String runId, long id) {
        return forRun(runId).stream().filter(d -> d.id() == id).findFirst();
    }

    private static List<String> lines(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split("\n"));
    }

    private static String bounded(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
