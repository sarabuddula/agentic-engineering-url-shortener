# Governance and Security Quality Checklist

**Purpose**: Gate check that human-governance controls, security design, compliance and change control, and audit tamper evidence are complete, clear, and consistent across the constitution, spec, plan, ADRs, and contracts.
**Created**: 2026-10-19
**Feature**: `001-agentic-url-shortener`

**Ownership**: The human candidate owns this checklist and its results; an independent reviewer re-runs it at the formal gate. No item requires executing code.

## Approval gates

- [ ] CHK061 — Are all gates G1..G8 defined with trigger, authority, and lifecycle states (PENDING → APPROVED | REJECTED | ESCALATED → second-timeout SAFE_STOPPED)? [Completeness — Plan §5, FR-024]
- [ ] CHK062 — Does every gate have a role mapping (APPROVER for G1..G4 and replan acceptance; RELEASE_OWNER for G5..G8; REVIEWER read/verify only) consistent across plan, ADR-008, and contract? [Consistency — ADR-008, Plan §5, contracts/openapi.yaml]
- [ ] CHK063 — Is silence-never-approval stated for every gate, with lack of response never treated as approval? [Clarity — Constitution III, Plan §5]
- [ ] CHK064 — Are gate rejection behaviors defined (bounded rework or governed termination, never automatic retry)? [Completeness — Plan §5]
- [ ] CHK065 — Is the escalation behavior specified as a single re-issue with an equal second window before safe-stop? [Completeness — PVT-005, Plan §5]
- [ ] CHK066 — Is the 5-minute approval timeout recorded as an approved assumption-derived validation target rather than an assignment-given number? [Measurability — PVT-005]
- [ ] CHK067 — Are blocked-progression attempts specified to be blocked and audited (SC-004)? [Completeness — SC-004, Plan §5]

## Distinct approver

- [ ] CHK068 — Is the distinct-approver rule stated for every gate including replan acceptance (requester and approver key identities must differ)? [Completeness — FR-046, D-006]
- [ ] CHK069 — Are self-approval and agent-approval attempts specified to return 403, leave the approval pending, and be audited? [Measurability — FR-046, contracts]
- [ ] CHK070 — Are agents barred from approving anywhere the design could allow it, with no exception path? [Consistency — Constitution III, FR-046]
- [ ] CHK071 — Is the exception governance rule stated (no exception against non-waivable principles II, III, VIII, X, XI; G6 valid only with every limitation individually approved and no mandatory FAIL)? [Consistency — Constitution VI, Plan §5, FR-037]

## Security

- [ ] CHK072 — Does the URL validation rule set cover every D-003 addition (IPv6 ::, fc00::/7, *.localhost, IPv4-mapped IPv6, decimal/octal IP forms, userinfo URLs, localhost names) plus scheme allowlist and size limits? [Completeness — D-003, Plan §8, FR-003]
- [ ] CHK073 — Is the resolver-mode switch defined (fixed-local demonstration default with committed resolution table; live mode) with its trust boundary and test-stub injection? [Clarity — Plan §1/§8, quickstart.md, PVT-003]
- [ ] CHK074 — Are rate limits A-008a..A-008d defined with per-IP/per-key scope and burst values, and the in-memory per-instance limitation disclosed? [Measurability — A-008, FR-047, research.md row 7]
- [ ] CHK075 — Are 429 responses specified with a retry hint (header and body) and no short code allocated? [Completeness — FR-047, contracts/openapi.yaml]
- [ ] CHK076 — Is key material handling specified (raw keys from a gitignored demo-only source; SHA-256 hashes stored; constant-time comparison; key ID only in audit records; no keys in logs)? [Completeness — D-004, ADR-008, NFR-001]
- [ ] CHK077 — Is the failed-auth rate limit (10/min per IP then block) defined with a configurable block duration? [Measurability — A-008d, D-004]
- [ ] CHK078 — Does secret scanning include the intentional demonstration fixture with a recorded exclusion rule? [Completeness — SEC-03, Plan §8]
- [ ] CHK079 — Are external input validation and normalization requirements carried into the plan's validation design without gaps (scheme, address rules, canonical form, size)? [Completeness — NFR-001, FR-002, FR-013]

## Compliance and change control

- [ ] CHK080 — Does every applicable policy check produce exactly one of PASS / FAIL / EXCEPTION-REQUESTED / NOT-APPLICABLE? [Measurability — Constitution VI]
- [ ] CHK081 — Is a failed mandatory policy check specified to block downstream workflow progression and release readiness? [Completeness — Constitution VI, FR-033, FR-037]
- [ ] CHK082 — Does the policy inventory name all 16 checks (SEC-01..03, CMP-01..02, PRV-01..02, AUD-01..02, DEP-01..02, LIC-01..02, CHG-01..03) with automated-vs-attestation marking? [Completeness — Plan §8, data-model.md]
- [ ] CHK083 — Is the versioned policy set recorded (POL-2026-10-18-v1) with every run recording the policy version evaluated? [Completeness — Constitution VI, FR-032]
- [ ] CHK084 — Are policy exceptions required to record applicable policy, reason, scope, approving authority, compensating control, approval timestamp, and expiry or review condition? [Completeness — Constitution VI, contracts/openapi.yaml]
- [ ] CHK085 — Is the offline degradation of DEP-02 defined (attestation using cached NVD data, degradation recorded and reported on the outcome)? [Clarity — Plan §8]
- [ ] CHK086 — Are changes to approved requirements, architecture, schemas, workflow states, security controls, and release criteria covered by formal impact analysis and change approval? [Completeness — C-005, Plan §9]
- [ ] CHK087 — Is the spec-delta list under C-005 complete and current (409 approved; Idempotency-Key header note recorded; 302/410/base-62 still proposals)? [Completeness — C-005, Plan §2]
- [ ] CHK088 — Are dependency approval and licensing policy checks named with their build-time tools and offline behavior (gitleaks, OWASP DependencyCheck, license-maven-plugin)? [Completeness — DEP-01..02, LIC-01..02, Plan §8]

## Audit tamper evidence

- [ ] CHK089 — Is the audit-chain hash definition specified (hash over prev-hash and event fields, append-only, no update/delete/purge exposed)? [Completeness — ADR-005, FR-048]
- [ ] CHK090 — Are all four tamper classes detectable per the verification design (altered, removed, reordered, truncated), with the affected position identified? [Completeness — FR-048, contracts/openapi.yaml]

## Notes

- Items are answerable from the committed documents without running code.
- Any unchecked item at the gate is a finding against the document set, not against an implementation.
- IDs continue in delivery-quality.md (CHK091+).
