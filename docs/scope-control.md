# Scope-Control Board (C-006)

Living reference for every scope-control checkpoint (T050, T066, T084). Created by T013, ID-level cut list by T125.
Source: plan section 14 (Delivery Sequence), amended by the fourth governed change of 2026-10-21 (tasks.md Amendment
Log, spec Amendment Appendix C, plan Amendments 2026-10-21).

## Decision record

| Field | Value |
|---|---|
| Decision | Deeper cut of optional and hardening work; add T128 (real test-suite execution in N09) |
| Decided by | Shilpa (human candidate) |
| Date | 2026-10-21 |
| Recorded words | "go with that" (deeper cut plus real test-execution agent); "confirm adjusted cut" (after never-cut conflicts were reported) |
| Proposed by | Claude Code (AI assistant); the assistant does not approve |
| Pending | T125 confirmation of this file as written, including three keeps added after the confirmation (T105, T108, T123; see "Kept to protect never-cut work") |

## Timebox status (disclosed)

Planning ran 2026-10-18 to 2026-10-20 (constitution, specification, clarification, plan, ADRs, checklists, tasks,
three analyze runs, independent review). Implementation started 2026-10-20 (T001). The 2-3 day timebox of C-006 is
exceeded. This is disclosed, not hidden. This cut is the governed response: it keeps every capability the assignment
names and every never-cut item, and moves hardening beyond the assignment to the backlog as accepted limitations.

Remaining effort estimate after this cut: about 29-34 focused hours (assistant estimate, not a measurement).

## Never cut

T101, T087-T091, T094, T099, T100, mandatory validation and reviewer evidence (plan section 14), and everything they
depend on. Required analytics reporting (FR-012) is never cut. The three scenarios are never cut.

## Must-have scope (delivery slices, plan section 14)

| Slice | Tasks | Milestone |
|---|---|---|
| 1-2. Baseline, walking skeleton | T001-T014, T125 (cut list, with T013) | done through T010; T011-T014 open |
| Foundational | T015-T023, T024 (reduced), T025, T026 (reduced), T124 | Day 1 |
| 3. Core URL behavior | T027-T041, T044, T045, T046 (reduced), T047, T048, T102, T108, T109, T113; checkpoint T050 | end of Day 1 |
| 4. Orchestration state model | T051-T058, T105, T118, T127 | Day 2 AM |
| 5. Approval governance | T059-T064, T119, T110; checkpoint T066 | mid Day 2 |
| 6. Reliability controls | T067, T068, T071-T072, T073, T074, T075, T076, T077, T078, T079, T122, T123, T128 | Day 2 PM |
| 7. Observability | T080-T083, T085, T086, T104 (reduced) | Day 2 PM |
| 8. Three scenarios | T069, T070, T087, T088, T089 (includes folded T116), T117, T106, T107; checkpoint T084 | end of Day 2 |
| 9. Release readiness | T090 (includes folded T112), T091-T096, T098-T101 | Day 3 |

Critical path: T011 -> T014 -> T018/T019 -> T021 -> T025 -> T027-T041 -> T050 -> T051-T058 -> T059-T061 -> T066 ->
T074-T078 -> T087-T089 -> T084 -> T090-T094 -> T099 -> T100.

## Cut list (fourth governed change, 2026-10-21)

### Deferred to backlog (recorded as accepted limitations, approved individually at T093/G6)

| Task | What is deferred | What remains | Spec reference |
|---|---|---|---|
| T042 | Failed-authentication per-IP limiting (A-008d) | 401 on bad keys; rate-limit audit records move into T041 | FR-047, L-SC-01 |
| T043 | Custom storage health check | Actuator default DataSource indicator (FR-010 still met; plan tier 1) | FR-010 |
| T049 | Separate quickstart walkthrough update | Quickstart brought current once, in T095 | US-001 |
| T065 | Control-plane role-matrix security tests | 401, 403 and self-approval tests in T059-T061 | FR-045, L-SC-06 |
| T097 | ADR evidence pass | ADR validation covered by the suite and traceability (T090) | ADR-001..009 |
| T103 | Per-key control-plane rate limit (A-008c) | Per-IP creation and redirect limits (A-008a/b) | FR-047, L-SC-01 |
| T111 | Bucket4j capacity-pressure test | Bounded bucket map implemented in T041 | FR-047, L-SC-01 |
| T115 | Dedicated concurrent-resolution test | Concurrent creation and duplicate tests (T035) | FR-008, L-SC-02 |
| T120, T121 | Staged revision, HELD nodes, replan cap 2 (A-017) | Replanning with re-validation and human acceptance (T071-T073) | FR-029, L-SC-03 |
| T126 | Rework cycles and rework cap 2 (A-016) | A rejection ends the path in governed termination | FR-024(c), L-SC-04 |

### Folded (same evidence, fewer tasks)

| Task | Folded into |
|---|---|
| T112 coverage map | T090 traceability matrix (coverage section) |
| T116 mid-run ambiguity on one path | T089 single-run demonstration |
| T114 red-first audit | Applied as a rule in every implementation task (red test committed first, expected failure recorded) |

### Reduced

| Task | Reduced to |
|---|---|
| T024 | Additive-only `/api/v1` rule documented in the contract (plan tier 2); no contract-diff test |
| T026 | H2 file mode and repository IT; PostgreSQL portability seam deferred (backlog already lists PostgreSQL) |
| T046 | URL-rejection matrix only; no-secrets-in-logs is covered by T022 |
| T104 | end_to_end_latency_ms per run; p95 deferred |

### Kept although first proposed for deferral (never-cut dependencies)

| Task | Reason kept |
|---|---|
| T045 | PVT-001/002 numbers are required by T094 |
| T106, T107 | DEP-02, PRV-02 and LIC-02 are mandatory release checks (data-model.md); T094 depends on them |
| T117 | Prerequisite of T089; timeout to safe-stop evidence |
| T122 | Prerequisite of T078 (PVT-006 kill-and-restart) |

### Kept to protect never-cut work (added after "confirm adjusted cut"; confirm at T125)

| Task | Reason kept |
|---|---|
| T105 | Prerequisite of T101 (FR-044 completion gate, never cut) |
| T108 | FR-012 requires not-found analytics outcomes; FR-012 is never cut |
| T123 | Without a separate transaction, a rolled-back operation can erase its own failure audit (T076, T079 evidence) |
| T109 | Restored after analyze run 4 (X1, human decision 2026-10-21): the replay-after-expiry behaviour decided 2026-10-19 needs test evidence |

### Added

| Task | Scope |
|---|---|
| T128 | N09 testing invokes a real test suite (whitelisted operation TS) and records the actual outcomes as TEST_RUN evidence, labelled LIVE |

## Further cut order (only at a checkpoint, only by human decision)

1. T083 depth beyond the PVT-008 fields.
2. Simplify demonstration fixtures (T069 TTL fixture).
3. T128 falls back to recording an explicitly labelled SIMULATED test outcome (disclosed as a limitation).

Nothing on the never-cut list is ever in this order.

## Checkpoints

| Checkpoint | When | Pass condition | If not met |
|---|---|---|---|
| T050 | end of Day 1 | core URL slice green, PVT-001..003 reported | apply further cut order step 1 |
| T066 | mid Day 2 | state model and approval governance green | apply step 2 |
| T084 | end of Day 2 | three scenario runs captured | Day 3 for validation and rework only |

## Stop conditions (plan section 14, unchanged)

A discovered constitution violation; a mandatory PVT failing with no fix inside the remaining buffer; an approval gate
blocking scenario completion beyond its escalation window; timebox exhaustion with mandatory work incomplete. The
honest outcome is then NOT READY with disclosed gaps, never fabricated evidence (C-004).
