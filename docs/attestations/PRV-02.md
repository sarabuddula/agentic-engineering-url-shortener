# Attestation PRV-02 - Privacy

Check (POL-2026-10-18-v1): no PII in logs or analytics beyond the approved minimum.
Approved minimum (spec FR-012, FR-015, A-002): redirect events store short code, timestamp and outcome only; no client
IP, user agent or referrer is stored; logs carry request_id/run_id, never API keys or target URLs with credentials.

## Checklist (answer each: YES / NO / NOT CHECKED, with a note)
1. REDIRECT_EVENT columns (src/main/resources/schema.sql) hold no IP, user agent, referrer or other client data: ____
2. A sample of application logs from a test run (target/surefire-reports/*-output.txt) shows no client IP, no API key,
   no X-API-Key value, no full target URL with user info: ____
3. Rate limiting keeps client identities in memory only and never persists them (ClientRateLimiter): ____
4. Audit events record key IDs only, never raw keys (AuditEntry reasons, KEY_IDENTITY stores hashes): ____
5. Anything else found: ____

## Evidence reviewed
(files, commands, commit SHA you reviewed)

## Decision
ATTESTED / NOT ATTESTED: ____
Limitations or follow-ups: ____

Attested by (key ID, never a raw key): ____
Role: RELEASE_OWNER
Date: ____
Reviewed commit: ____
