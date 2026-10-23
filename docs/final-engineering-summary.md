# Final Engineering Summary

Agentic Software Engineering System — URL Shortener. Decision owner: the human candidate (Shilpa). Built with SpecKit
and Claude Code (AI-use disclosure in the README). Every claim names a file, commit, run or command a reviewer can
check. Legend: **[C]** confirmed requirement, **[A]** approved assumption, **[M]** demonstration measurement,
**[D]** deferred, **[L]** accepted limitation.

## 1. Executive outcome and release readiness
Final human release decision (T099, 2026-10-22): **READY WITH ACCEPTED LIMITATIONS**, based on run run-85f0038b
(REPLAY: the SCN-A requirement re-executed on code a LIVE run had built; system recommendation READY_WITH_ACCEPTED_LIMITATIONS, G7 by the release owner, COMPLETED, chain verified). Six
limitations were accepted individually at G6 and apply to the product (section 19). Record:
`specs/001-agentic-url-shortener/approvals/2026-10-22-t084-t099-checkpoint-and-release.md`. Build: `mvn clean verify`
exit 0, 424 tests (302 unit, 122 integration). Traceability: `scripts/trace-manifest.sh check` CLEAN.

## 2. Objective, scope and timebox outcome
Objective: a locally runnable prototype of an agentic SDLC orchestration system (control plane) applied to a URL
shortener (application plane), with human oversight, policy guardrails, reliability and auditability. Scope was cut
twice by governed decisions (2026-10-21, 2026-10-22; `docs/scope-control.md`). Timebox: the work spans 2026-10-18 to
2026-10-22, five days against the 2-3 day timebox (C-006) - planning took 2026-10-18 to 10-20; disclosed in spec
Amendment Appendix C and here.

## 3. Confirmed requirements, assumptions, exclusions, deferred scope
`spec.md`: FR-001..FR-049 [C], NFR-001..011, SC-001..007, PVT-001..008, assumptions A-001..A-018 with approval state,
exclusions E-001..E-005. Amended by governed decision: A-006 default link TTL 7 days -> 24 hours (SCN-B, 2026-10-22);
A-018 join-wait bound cut (L-SC-07). Deferred scope [D]: `docs/scope-control.md` (11 tasks; T097 was completed on 2026-10-22).

## 4. Architecture overview and accepted ADRs
`docs/architecture.md` (diagrams of both planes and the N01-N14 graph). ADR-001..009 accepted by the candidate
(`docs/adr/adrs.md`): Java 21; Spring Boot 3.5 (JPA replaced by Spring JDBC, 2026-10-21); H2 file mode; one fat jar;
relational workflow state with a transactional hash chain; base-62 codes with database-enforced idempotency; explicit
dependency graph with governed replanning; role-scoped keys; bounded retry, fallback, safe-stop, rollback vs
compensation.

## 5. API contracts and schema deliverables
`contracts/openapi.yaml` (application plane: create, redirect, status lookup, analytics, health; control plane: runs,
approvals, policy exceptions, resume, replan, history, verify, release readiness, metrics). Contract tests validate
every response against it. Contract changes during implementation and their approvals: plan section 2. Schema:
`src/main/resources/schema.sql`, `data-model.md`.

## 6. Orchestration model, dependency graph, state persistence
14 persisted stages with DEPENDENCY, CONDITIONAL and SYNC edges; parallel N09/N10 joined at N11; N08 fans out tasks
with explicit task dependencies; every transition is checked against state machines and committed with its audit
event; optimistic locking; resumption from persisted state. Per-node operation whitelist enforced (0bc2f00).
What the agents are: deterministic, rule-based executors with no language-model calls at runtime; design text and
code come from the engineer with AI assistance outside the runtime and enter runs as versioned artifacts and marked
commits (`docs/architecture.md`, "What is agentic and what is human").

## 7. Human approvals, governance gates and decision lineage
Gates G1-G8 plus REPLAN_ACCEPTANCE and CHANGE_APPROVAL with role mapping (ADR-008); approver key must differ from the
requester's; self- and agent-approval refused and audited; one escalation then safe-stop, silence never approval.
Every decision is in DECISION_LINEAGE with rationale, rejected alternatives and artifact versions, visible in
`/history`. Demonstration-run decisions are HUMAN; fixture approvals in tests are labelled SIMULATED.

## 8. Compliance and change-control results
Versioned policy set POL-2026-10-18-v1, 16 checks, each with one outcome per run. Changes to approved artifacts went
through CHANGE_APPROVAL (A-006 in SCN-B) and governed amendments (tasks.md Amendment Log; approvals/). Evidence sources per
check: `docs/architecture.md` ("How a run executes", step 5) and the N12 provider; results per run in the exports.

## 9. Policy exceptions, compensating controls, expiry, approvals
One exception in the demonstration runs: CHG-03 in run-934a50a1 (exception 1, G5 approval 8 by the release owner,
compensating control: SC-002/003/006/007 measured on the demonstration runs, expiry 2026-10-29). Exceptions are
requested through `POST /runs/{runId}/policy-exceptions`; only an approved, unexpired exception stops a failing check
from blocking (`PolicyExceptionTest`).

## 10. Security controls, findings, residual risks
URL validation with the D-003 rule set (scheme allowlist, internal/local/IPv4-mapped/numeric forms, userinfo,
canonical form; `UrlRejectionMatrixIT`); per-IP rate limits with 429 and retry hint; role-scoped hashed keys, key ID
only in audit; gitleaks clean over the full history. Dependencies: 17 OSV.dev advisories found, Jackson, Tomcat and
log4j upgraded, two spring-webmvc advisories remain (features unused) - DEP-02 attested, no automated NVD scan
(`docs/attestations/DEP-02.md`). Residual: control-plane per-key and failed-auth limits not enforced [L] (L-SC-01);
the status lookup is not rate-limited (disclosed in the contract).

## 11. Reliability: retry, fallback, rollback/compensation, safe-stop
Transient failures retried at most 3 times with backoff and a 60 s bound; unknown failures permanent; recorded
fallbacks; uncommitted rows roll back (verified); committed code compensated by a revert commit, rollback refused and
audited; failure records survive rollbacks (own transaction); safe-stop with cause and governed resumption; boot
recovery with effect verification; kill-and-restart without lost or duplicated effects (`KillRestartIT`; live in
run-4738769c). Found and fixed by that test: H2 lost commits on a hard stop (WRITE_DELAY=0).

## 12. MTTR
Definition per the constitution: total recovery duration / recovered failure events; governance wait excluded and
reported separately with a labelled secondary figure; unrecovered failures counted separately. Demonstration
database (`/api/v1/metrics/demo`, T092) [M]: primary MTTR 365 ms over 4 recovered events, 244 172 ms including
governance wait, 4 unrecovered events (stops left for the human), governance wait 975 229 ms in total.

## 13. Observability, auditability, evidence integrity
Per-run SHA-256 hash chain verified at N12 and re-verified after the terminal transition; `/verify` reports four
tamper classes plus head mismatch with the position. `/history` returns the full lineage. Reconstruction from the
repository alone: `scripts/reconstruct-run.py` recomputed all eight runs' chains from the committed exports (T092).
Structured logs carry request and run IDs, never keys.

## 14. Greenfield (SCN-A)
`docs/evidence/scn-a-greenfield.md`. LIVE run run-934a50a1 built the status lookup red-first with no unnecessary
clarification, design and release decided by the candidate, and stopped at the completion gate (two missing
conditions, explained). The completed SCN-A run is REPLAY (run-4738769c).

## 15. Brownfield (SCN-B)
`docs/evidence/scn-b-regression.md`. LIVE run run-8306f656: impact analysis before any change, CHANGE_APPROVAL and G2
by the candidate, the A-006 default changed to 24 hours, exactly two test expectations changed (both predicted by the
impact analysis, red first), full regression suite green, COMPLETED READY. Disclosed mismatch: the analysis listed Java
components; only `application.yml` had to change.

## 16. Ambiguous requirement (SCN-C)
`docs/evidence/scn-c-replay.md`. "Add support for links." - N02 found two material ambiguities, the LIVE runs stopped
at G1 before any implementation; the candidate's answer became requirement version 2 and the run resumed at the
correct state. The completed SCN-C runs are REPLAY (the clarified requirement maps to existing create and resolve).

## 17. Test strategy and executed validation
Test-first (`docs/evidence/red-first-audit.md`: 59 tasks red first; T006-T008 from the setup phase are deviations).
`mvn clean verify`: 424 tests across unit, contract, persistence, integration, orchestration, approval, reliability,
kill-and-restart, concurrency, performance, security, ArchUnit and end-to-end. PVT-001..008: `pvt-report.md` - all met
or reported; PVT-004 (retries) is covered by tests only, no live run hit a retry. Checklist re-run (T096): 113 PASS,
2 ACCEPTED, 0 open.

## 18. Traceability
`traceability/mapping-manifest.yml` (80 requirement IDs: ADRs, tasks, tests, documents, evidence with SHA-256) and
`traceability/matrix.md`; `scripts/trace-manifest.sh check` CLEAN in both directions.

## 19. Known limitations, technical debt, deferred enhancements
Accepted at G6 (`docs/evidence/t093-accepted-limitations.md`): L-SC-01 control-plane rate limits; L-SC-02 no dedicated
concurrent-resolution test; L-SC-03 staged revisions, HELD nodes, replan cap; L-SC-04 rework cycles; L-SC-06 full
role-matrix tests; L-SC-07 join-wait bound and single-path suspension (no gate on a parallel path). Also: runtime
agents are rule-based; DEP-02 without an automated scan; Spring Boot 3.5 past open-source support; H2 only; the eight
demonstration runs predate the whitelist enforcement; 48 early commits carry an AI co-author line
(`t098-history-review.md`). Deferred tasks: `docs/scope-control.md`.

## 20. Reviewer verification points
1. `mvn clean verify` (424 tests). 2. Quickstart walkthrough (`quickstart.md`). 3. Reconstruct any run:
`python3 scripts/reconstruct-run.py docs/evidence/runs/<run>.json`. 4. `scripts/trace-manifest.sh check`.
5. Human decisions: `specs/001-agentic-url-shortener/approvals/`. 6. Attestations: `docs/attestations/`.
7. Executed reviewer walkthrough: `docs/evidence/t092-reviewer-walkthrough.md`.

## 21. Final engineering judgment, blockers, next actions
The orchestration core - persisted graph, gates, policy blocking, recovery, audit - is implemented and evidenced,
including defects the demonstration runs found and fixed (run start, clarification, recovery after resumption,
parallel artifact versions, boot recovery race, lost commits on a hard stop). No blocker remains for the submission.
Next steps, each under change control: an LLM-backed design and implementation executor behind the existing whitelist
and gates; the deferred controls (L-SC-01, -03, -04, -07); PostgreSQL; Spring Boot 4; an automated DEP-02 scan with an
NVD key.
