# Delivery and Quality Checklist

**Purpose**: Gate check that the testing requirements, documentation, repository evidence, release-readiness decision, and assessment submission are complete, clear, and consistent with the constitution and plan.
**Created**: 2026-10-19
**Feature**: `001-agentic-url-shortener`

**Ownership**: The human candidate owns this checklist and its results; an independent reviewer re-runs it at the formal gate. No item requires executing code.

## TDD and testing requirements

- [x] CHK091 — Does the plan require red-green-refactor TDD for domain and orchestration behavior, with each failing test shown failing first for the expected reason? [Completeness — Constitution IV, Plan §10]
- [x] CHK092 — Are all required test categories mapped to a framework and execution plugin (domain unit, API contract, persistence, integration, orchestration transition, reliability, security, e2e; Surefire/Failsafe split)? [Completeness — Plan §10, research.md rows 4/13]
- [ ] CHK093 — Does every validation target PVT-001..PVT-008 have a named test category and measurement method? [Traceability — Plan §10/§11]
- [x] CHK094 — Are the performance validation targets (PVT-001 ≥99% resolution success, PVT-002 ≥95% under 200 ms, PVT-003 100 concurrent creations) recorded as approved demonstration targets for the local environment? [Measurability — NFR-007, PVT-001..003]
- [x] CHK095 — Is the API contract test specified to validate against contracts/openapi.yaml using the approved validator, with hand-written schema assertions as the recorded fallback? [Completeness — Plan §10, research.md row 9]
- [x] CHK096 — Are ArchUnit rules specified for domain purity (no Spring in domain) and plane separation (shortener never imports orchestration)? [Completeness — Plan §3/§10]
- [x] CHK097 — Is task completion required to be based on executed validation (suite output), not merely generated code? [Completeness — Constitution IV/XI, Plan §14]
- [x] CHK098 — Are the rate-limit tests specified (429, no short code allocated, audited, configurable low limits) as distinct test cases? [Completeness — FR-047, A-008, research.md row 7]

## Documentation

- [x] CHK099 — Is documentation required to evolve with implementation rather than remain a static artifact? [Completeness — Constitution X]
- [ ] CHK100 — Do the quickstart reviewer steps cover the full US-005 reconstruction (clone, verify, walkthrough, history/verify/release-readiness/metrics, traceability both directions)? [Completeness — quickstart.md, US-005, SC-006]
- [ ] CHK101 — Is the resolver-mode documentation consistent between plan and quickstart (fixed-local table location and contents, live mode, stub injection for tests)? [Consistency — Plan §1/§8, quickstart.md]
- [ ] CHK102 — Are demo keys documented as demo-only with raw keys never in the file, logs, or commits (helper script generating raw keys to shell exports, hashes to the gitignored file)? [Clarity — A-009, D-004, quickstart.md]

## Repository and GitHub evidence

- [x] CHK103 — Are commits required to be small, logically coherent, intent-describing, and reflective of the actual execution sequence? [Completeness — Constitution X]
- [x] CHK104 — Is the traceability matrix specified as generated from the mapping manifest joined with persisted tables, clean in both directions? [Measurability — CHG-03, Plan §13]
- [x] CHK105 — Are AI-generated artifacts required to receive human review and ownership before commit? [Completeness — Constitution X]
- [x] CHK106 — Is the manifest SHA semantics stated (records the SHA of the commit being referenced, never of its own commit)? [Clarity — Plan §13]
- [x] CHK107 — Is fabricated evidence prohibited with the rule carried into task definitions (a command not reported as executed unless executed; success not reported unless succeeded)? [Completeness — Constitution X, Plan §14]

## Release readiness

- [x] CHK108 — Are the three release outcomes (READY / READY WITH ACCEPTED LIMITATIONS / NOT READY) defined with the decision owned by a human? [Completeness — FR-037, Plan §9]
- [x] CHK109 — Does release readiness fail on a mandatory policy violation or an unapproved or expired exception? [Completeness — Constitution VI, FR-037]
- [x] CHK110 — Are accepted limitations individually approved and listed, with residual risks and limitations disclosed in the release decision? [Completeness — CL-5, Constitution XI]
- [x] CHK111 — Is the minimum defensible release-readiness outcome for the 2-3 day timebox defined in the delivery sequence? [Completeness — Plan §14]
- [x] CHK112 — Does the forced-FAIL demonstration (POL-DEP-01, simulated undeclared dependency) have a defined expected outcome (NOT READY, blocking reasons reported)? [Scenario coverage — Plan §8/§11]

## Assessment submission

- [x] CHK113 — Does the delivery sequence include day-level milestones, critical path, cut order, scope-control checkpoints, and stop conditions? [Completeness — Plan §14, C-006]
- [x] CHK114 — Is the G8 final submission (repository tag/push) defined with its authority and release-owner key role? [Completeness — Plan §5, ADR-008]
- [x] CHK115 — Are deferred optional items recorded as a backlog that cannot displace mandatory validation and reviewer evidence? [Completeness — Plan §14, Constitution Workflow]

## Notes

- Items are answerable from the committed documents without running code.
- Any unchecked item at the gate is a finding against the document set, not against an implementation.
- Performance tuning is out of scope for this checklist by instruction; only the existence and measurability of validation targets is checked.
