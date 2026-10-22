# Attestation PRV-02 - Privacy

Check (POL-2026-10-18-v1): no PII in logs or analytics beyond the approved minimum.
Approved minimum (spec FR-012, FR-015, A-002): redirect events store short code, timestamp and outcome only; no client
IP, user agent or referrer is stored; logs carry request_id/run_id, never API keys or target URLs with credentials.

## Checklist (answer each: YES / NO / NOT CHECKED, with a note)
1. REDIRECT_EVENT columns (src/main/resources/schema.sql) hold no IP, user agent, referrer or other client data: YES -
   the columns are ID, SHORT_LINK_ID, ATTEMPTED_CODE, OCCURRED_AT and OUTCOME; the only writer
   (JdbcAnalyticsStore) inserts exactly those.
2. A sample of application logs from a test run (target/surefire-reports/*-output.txt) shows no client IP, no API key,
   no X-API-Key value, no full target URL with user info: YES - Surefire writes no *-output.txt files in this project,
   so the console output of `mvn test` (exit 0) and `mvn clean verify` (exit 0) and the application log of the
   2026-10-22 demonstration runs were scanned instead: 0 occurrences of the `fixture-only-` test keys, 0 of the four
   demo raw keys, no X-API-Key values, no URLs with user info; the only IPv4-shaped match was `21.0.12.1` (the Java
   version in the startup line), not a client IP.
3. Rate limiting keeps client identities in memory only and never persists them (ClientRateLimiter): YES - a bounded
   in-memory ConcurrentHashMap of token buckets; no JDBC or INSERT in the class.
4. Audit events record key IDs only, never raw keys (AuditEntry reasons, KEY_IDENTITY stores hashes): YES -
   KEY_IDENTITY has KEY_HASH only and .demo-keys.yml holds key IDs, roles and hashes only; both H2 database files
   (./data and the demonstration data directory) contain 0 occurrences of the four demo raw keys while the key hashes
   are readable in the same files (so the search is meaningful); audit reasons name key IDs (e.g. "by demo-approver").
5. Anything else found: the checklist's item 2 command does not work in this project (no *-output.txt files); the
   console output was used instead, as noted above.

## Evidence reviewed
- `sed -n '/CREATE TABLE IF NOT EXISTS REDIRECT_EVENT/,/);/p' src/main/resources/schema.sql`, same for KEY_IDENTITY
- `grep -rn "INSERT INTO REDIRECT_EVENT" src/main/java`; ClientRateLimiter.java read
- `mvn test` and `mvn clean verify` console output and the demonstration application log, searched for the
  `fixture-only-` prefix, the demo raw keys (counts only, keys never printed), X-API-Key values, IPv4 addresses and
  URLs with user info
- H2 files searched for the demo raw keys (counts only) and for a key hash (control)
- Commit reviewed: 90e394a
- Prepared with AI assistance (Claude); reviewed, confirmed and decided by the human candidate.

## Decision
ATTESTED / NOT ATTESTED: ATTESTED
Limitations or follow-ups: log review covers the test suites and the 2026-10-22 demonstration runs, not every possible
log line; the item 2 command in this form should be corrected to scan console output.

Attested by (key ID, never a raw key): demo-release-owner
Role: RELEASE_OWNER
Date: 2026-10-22
Reviewed commit: 90e394a
