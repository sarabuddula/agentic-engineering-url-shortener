# Architecture and Orchestration Quality Checklist

**Purpose**: Gate check that the architecture, ADRs, and orchestration design are consistent with the spec and plan, and that the dependency-graph model, state persistence, replanning, failure semantics, and recovery design are complete and clear.
**Created**: 2026-10-19
**Feature**: `001-agentic-url-shortener`

**Ownership**: The human candidate owns this checklist and its results; an independent reviewer re-runs it at the formal gate. No item requires executing code.

## ADR and plan consistency

- [ ] CHK031 — Is every accepted ADR-001..ADR-009 consistent with plan §12 and the corresponding research.md rows? [Consistency — ADR-001..ADR-009, Plan §12]
- [ ] CHK032 — Does any accepted ADR conflict with an approved spec constraint C-001..C-006 or a confirmed requirement? [Conflict — ADRs, Spec]
- [ ] CHK033 — Does each ADR record rejected alternatives with pros/cons/risks, rationale, reversibility, and a validation approach? [Completeness — ADR-001..ADR-009]
- [ ] CHK034 — Does the data model support every ADR decision (STAGE_EDGE, TASK_DEPENDENCY, hash-chained AUDIT_EVENT, run_revision, FAILURE_EVENT)? [Consistency — data-model.md, ADR-005/007/009]

## Graph versus linear chaining

- [ ] CHK035 — Does the orchestration design satisfy "more than sequential agent chaining" with an explicit stateful dependency graph? [Completeness — Constitution II, ADR-007]
- [ ] CHK036 — Does every stage N01..N14 define inputs, outputs, entry criteria, exit criteria, ownership, and failure behavior? [Completeness — Constitution II, Plan §3]
- [ ] CHK037 — Does STAGE_EDGE express all required edge kinds (sequencing, parallel fan-out, synchronization joins, conditional branching)? [Completeness — data-model.md]
- [ ] CHK038 — Is TASK_DEPENDENCY restricted to task-level dependencies, distinct from stage-graph edges? [Consistency — data-model.md, ADR-007]
- [ ] CHK039 — Is the bounded thread pool for parallel executors disclosed as a minimal implementation with its upgrade path noted? [Clarity — Plan §3, research.md row 12]

## Persisted state

- [ ] CHK040 — Is every workflow state transition specified to commit atomically with its audit event? [Consistency — ADR-005, FR-048]
- [ ] CHK041 — Is artifact provenance persisted for every produced artifact (type, version, content hash, provenance label)? [Completeness — Constitution II, data-model.md ARTIFACT]
- [ ] CHK042 — Is cross-stage context persistence specified so a resumed run reconstructs what each stage saw? [Completeness — Constitution II, Plan §4]

## Parallel paths and synchronization

- [ ] CHK043 — Are synchronization join semantics defined (wait-for-all, failure propagation from parallel branches)? [Clarity — Plan §3]

## Dynamic replanning

- [ ] CHK044 — Are replanning triggers defined (upstream output change) with the mutation scope (downstream of the change point)? [Completeness — FR-029, D-006]
- [ ] CHK045 — Does replanning preserve governance by re-validating affected artifacts, evidence, and approvals? [Completeness — FR-029]
- [ ] CHK046 — Is replan acceptance specified to require an approver distinct from the replan requester, with run_revision lineage and rejection leaving the prior revision in force? [Consistency — FR-046, ADR-007/008, data-model.md]

## Controlled autonomy

- [ ] CHK047 — Are agent autonomy bounds defined per node and auditable? [Completeness — Constitution II, Plan §3]
- [ ] CHK048 — Can every agent decision be reconstructed from persisted records alone? [Measurability — FR-023, FR-030, PVT-007]

## Retry bounds

- [ ] CHK049 — Are retry bounds defined for every external-effect operation (count, backoff, terminal deadline)? [Completeness — Plan §6, PVT-004]
- [ ] CHK050 — Is the ≤3 retries / 60 s terminal rule stated as a validation target recorded as an assumption where the assignment gives no number? [Measurability — PVT-004]
- [ ] CHK051 — Is the unknown-failure default-to-permanent classification stated, and idempotency required for every repeatable operation used in recovery? [Completeness — D-001, Constitution VIII, Plan §6]

## Rollback versus compensation

- [ ] CHK052 — Is every external-effect operation classified reversible or irreversible in the rollback-vs-compensation table? [Completeness — Plan §6]
- [ ] CHK053 — Is at least one provably reversible operation identified (uncommitted transaction rows) so rollback is demonstrable? [Completeness — Plan §6]
- [ ] CHK054 — Is the N08 committed-code case classified as compensation (revert commit), not rollback, consistently across plan and data model? [Consistency — data-model.md, Plan §6]
- [ ] CHK055 — Is CMP-02 semantics defined (what the check verifies about rollback/compensation coverage)? [Clarity — data-model.md]

## Safe-stop

- [ ] CHK056 — Are safe-stop conditions enumerated (unclassified failure; approval timeout through escalation) and distinguished from the policy-failure NOT READY path? [Consistency — ADR-009, Plan §6]
- [ ] CHK057 — Is the failed-mandatory-policy-check path defined end-to-end (N12 FAIL → NOT READY persisted → N14 reporting only; N13 not taken)? [Completeness — FR-033, FR-037, Plan §4]
- [ ] CHK058 — Are deterministic terminal outcomes specified after retry exhaustion? [Completeness — Constitution VIII, FR-025..FR-028]

## Resumption

- [ ] CHK059 — Are resumption conditions recorded (cause addressed, governed approval where required) before a run may resume? [Completeness — FR-028, Plan §6]
- [ ] CHK060 — Is the after-effect/before-completion-record case handled explicitly in the resumption design (reconcile or safe-stop, never duplicate)? [Completeness — PVT-006, Plan §6]

## Notes

- Items are answerable from the committed documents without running code.
- Any unchecked item at the gate is a finding against the document set, not against an implementation.
- IDs continue in governance-security.md (CHK061+).
