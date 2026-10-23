# Final Independent Assessment (execution guide section 23)

Read-only assessment, 2026-10-22, of the repository at c6dc9f9 against the assignment ("Interview Assignment: Build an
Agentic Software Engineering System - URL Shortener") and the execution guide. **Independence limit:** prepared by
Claude Code, which assisted with the development. Every score rests on executed evidence (suite runs, run exports,
database rows, reconstruction), not on the project's own documents. The human candidate reviews and owns this record.

Executed for this assessment: `mvn clean verify` on a fresh clone (302 unit and 122 integration tests, exit 0); the
service on a copy of the demonstration database (all eight runs match their exports); `scripts/reconstruct-run.py` on
all eight exports (every chain matches); `scripts/trace-manifest.sh check` (CLEAN); the TEST_OUTCOME rows per run;
every command in `docs/reviewer-guide.md`. Detail: `t092-reviewer-walkthrough.md`, `scenario-evidence-review.md`.

## Overall verdict
**PASS.** A panel that requires agents to produce the design and code at runtime would likely rate it
**BORDERLINE**; see "Orchestration concerns".

## Executive rationale
The orchestration layer the assignment calls the critical differentiator is real and evidenced. It has a persisted
N01-N14 graph with conditional and synchronization edges and entry and exit gates. Human approvals are enforced by
distinct keys. Retry, fallback, safe-stop and governed resumption are implemented, and a live kill-and-restart
recovered without duplicate effects. Sixteen versioned policy checks can block release. Every run reconstructs from the
repository through a hash-chained audit trail. Governance is unusually rigorous for a 2-3 day prototype: decisions are
recorded under `approvals/`, limitations accepted one by one, and evidence classed LIVE or REPLAY and HUMAN or
SIMULATED. The weaknesses are about what the runtime agents do. They are rule-based, and the design and code come from
the engineer outside the runtime. Two of the three scenarios completed only as REPLAY.

## Scoring matrix (0-5)

| # | Dimension | Score | Evidence or reason |
|---|---|---|---|
| 1 | Requirement understanding | 4 | spec with provenance tags, stable IDs, negative criteria, amendment log |
| 2 | Ambiguity management | 4 | N02 finds material ambiguity and the LIVE runs hold at G1; completion only REPLAY |
| 3 | Task decomposition | 4 | 128 dependency-annotated tasks; runtime decomposition from templates |
| 4 | Brownfield reasoning | 4 | impact analysis before the change, compared with the actual diff; over-predicted Java components (disclosed) |
| 5 | Orchestration effectiveness | 4 | coordinates the whole lifecycle with gates and policy; N08 waits for human commits |
| 6 | Stateful, non-linear design | 5 | persisted graph, conditional branches, sync join, boot recovery, revisions |
| 7 | Parallel execution and synchronization | 4 | N09/N10 joined at N11, task fan-out; single-path suspension cut (L-SC-07) |
| 8 | Controlled autonomy | 3 | per-node whitelist enforced, but the eight runs predate enforcement and agent autonomy is narrow |
| 9 | Human approval enforcement | 5 | self- and agent-approval refused and audited; expiry and escalation exercised live |
| 10 | Retry, fallback, timeout, safe-stop | 4 | all tested; safe-stop and timeout live; retry never exercised live |
| 11 | Rollback or compensation | 3 | correct semantics, tested (RollbackTest, CompensationTest); no demonstration run |
| 12 | Resume and recovery | 5 | live kill-and-restart with reconciliation in run-4738769c; KillRestartIT on the real jar |
| 13 | Dynamic replanning | 3 | content-hash invalidation and distinct-approver acceptance, one live accepted replan (REPLAY run); HELD nodes and cap deferred (L-SC-03) |
| 14 | Decision lineage | 4 | DECISION_LINEAGE rows with rationale, rejected alternatives and artifact versions |
| 15 | Traceability | 5 | hashed mapping manifest, clean in both directions, machine-checked |
| 16 | Architecture quality | 4 | separated planes enforced by ArchUnit; ADRs with rejected options; a single JVM is a disclosed limit |
| 17 | Code quality | 4 | sampled, not fully reviewed: small classes, explicit interfaces, no shell use in git calls |
| 18 | Test-driven development | 4 | 59 tasks red first with the deviations disclosed; three post-red test edits, each checked against the original |
| 19 | Test depth | 4 | 424 tests across unit, contract, persistence, orchestration, reliability, security, e2e |
| 20 | Security | 4 | URL rejection matrix, per-IP limits, hashed keys, gitleaks; control-plane limits deferred; DEP-02 via OSV.dev review |
| 21 | Reliability | 4 | classified failures, bounded retry, effect verification; H2 lost-commit defect found and fixed |
| 22 | Observability | 4 | correlated logs, metrics endpoint, audit spans; no distributed tracing (not needed for one JVM) |
| 23 | Auditability | 5 | per-run SHA-256 chain, tamper position reported, offline reconstruction |
| 24 | Greenfield scenario | 3 | LIVE run built the feature red-first but stopped at the completion gate; completed run is REPLAY |
| 25 | Brownfield scenario | 5 | LIVE, impact before change, predicted test changes only, COMPLETED READY |
| 26 | Ambiguous-requirement scenario | 3 | correct detection and hold; completed only as REPLAY; no replanning event |
| 27 | Documentation | 4 | complete set (summary, architecture, reviewer guide); volume is high |
| 28 | GitHub evidence | 4 | small, intent-describing commits in execution order; 48 AI co-author lines disclosed; five days against 2-3 |
| 29 | Defensibility of decisions | 5 | every change of scope or design has a recorded human decision and reason |
| 30 | Engineering judgment | 4 | honest NOT ATTESTED before remediation; scope cut under the timebox rather than evidence faked |

## Mandatory blocking gaps
None. Every requirement of the assignment has implemented, executed evidence or a limitation accepted at G6.

## Evidence strengths
Fresh-clone build and walkthrough; offline reconstruction of every run; database-enforced idempotency and concurrency
(PVT-003); live kill-and-restart; the completion gate refusing to declare completion on incomplete evidence (run-934a50a1);
the DEP-02 attestation first NOT ATTESTED, then remediated.

## Unsupported claims
None found. One citation corrected: `scn-a-greenfield.md` cited event #75 for the N09 test run, whose evidence is the
TEST_OUTCOME table, now recorded in `n09-test-outcomes.md`.

## Architecture concerns
One JVM and H2 file mode: no horizontal scale (disclosed; the assignment allows a prototype). Spring Boot 3.5 is past
open-source support.

## Orchestration concerns
The runtime executors are deterministic. Design text and code are produced by the engineer with AI assistance outside
the runtime, and N08 accepts them as marked commits. That is stated in the README and the architecture overview, and it
keeps every step reproducible. It is the main point a reviewer may contest against "agents execute multi-step work".
Staged revisions with HELD nodes, the replan cap, rework cycles and single-path suspension are deferred.

## Governance concerns
One person holds every role key; separation of duties is procedural (disclosed in ADR-008). The demonstration runs
predate the whitelist enforcement (0bc2f00), which is evidenced by tests only.

## Testing concerns
Retry and compensation have no live demonstration. Per-run N09 outcomes were not in the repository; they are now in
`n09-test-outcomes.md`, which also shows the SCN-B run's N09 selection did not include `LinkCreationIT` (covered by the
full suite instead).

## Security and reliability concerns
Two CRITICAL spring-webmvc advisories remain (features unused, attested). No automated NVD scan. Control-plane per-key
and failed-authentication limits are deferred (L-SC-01).

## Documentation and traceability concerns
The documentation is large; `docs/reviewer-guide.md` and the README "Start here" table are the entry points. The final
engineering summary was written before this assessment, while the guide puts it after; this assessment found nothing
that changes it.

## Git history assessment
276 commits, 2026-10-18 to 2026-10-22, red-test commits before implementation commits. Published history was not
rewritten (`t098-history-review.md`). Two tags will exist: `v1.0-submission` (earlier) and the final tag.

## Convergence (guide section 22)
Convergence was covered by T090 (traceability), T096 (checklists: 113 PASS, 2 ACCEPTED), the final engineering summary
and this assessment, not by a separate `/speckit.converge` run. That command appends unbuilt work as tasks. The only
unbuilt work is the 10 deferred tasks already listed in `tasks.md` and `docs/scope-control.md`, accepted at G6. Release
decision: READY WITH ACCEPTED LIMITATIONS (T099, human).

## Likely reviewer questions
1. Where does an agent write code? (Nowhere at runtime; by design, see the architecture overview.)
2. Why are the completed greenfield and ambiguous runs REPLAY?
3. Show a retry or compensation in a real run.
4. Why five days against 2-3?
5. How would this scale beyond one JVM?

## Prioritized remediation plan
1. (Done before tagging, docs only) Per-run TEST_OUTCOME summaries exported (`n09-test-outcomes.md`); #75 citation fixed.
2. (After submission, under change control) An LLM-backed design and implementation executor behind the existing
   whitelist and gates; a LIVE greenfield and ambiguous run to COMPLETED; HELD nodes and replan cap; PostgreSQL; Spring
   Boot 4; an automated DEP-02 scan.
