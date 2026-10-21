# ADR Set — Agentic Software Engineering System, URL Shortener
**Feature Branch**: `001-agentic-url-shortener` | **Date**: 2026-10-18 | **Status**: ADR-001..ADR-009 **Accepted** by the human candidate, 2026-10-18 (Human Gate 4)

---

## ADR-001: Language and Runtime — Java 21 LTS

**Status**: Accepted (human candidate, 2026-10-18); pool wording amendment re-accepted 2026-10-21 (T124, specs/001-agentic-url-shortener/approvals/2026-10-21-t014-t124.md)

**Context**: Single-process prototype delivered in a 2-3 day timebox (C-006); orchestration and domain logic in one statically typed codebase; typed workflow states and audit invariants are load-bearing.

**Decision Drivers**: C-001 (language/runtime), C-006 (timebox), human-candidate fluency, test tooling breadth (constitution IV), NFR-004 maintainability.

**Options Considered**:
- Java 21 LTS — pros: candidate fluency, typed state/audit invariants (sealed types, records), a simple bounded thread pool for the parallel executors (ADR-007; virtual threads are an optional later upgrade); cons: JVM startup vs native; risks: none material.
- TypeScript/Node 22 — pros: single-language full stack, npm ecosystem; cons: weaker runtime typing on state/audit invariants, lower fluency; risks: invariant violations surface at runtime.
- Python 3.12 — pros: fast iteration; cons: weaker concurrency and typing discipline for audit invariants; risks: same, amplified under PVT-003.

**Decision**: Java 21 LTS.

**Rationale**: Fluency under the timebox with typed workflow states and audit invariants; LTS support; a simple bounded thread pool serves the parallel executors without a reactive stack.

**Consequences**: Maven toolchain; Spring Boot 3.x baseline (ADR-002); fat-jar run model (ADR-004).

**Reversibility**: Low-moderate — language switch is a rewrite; mitigated by repository interfaces (ADR-003) and plane separation.

**Traceability**: C-001, C-006, NFR-004; plan §12, §14 (engineering-baseline task); research.md row 1.

**Validation**: `mvn clean verify` executes on JDK 21 at the engineering-baseline task (planned, not yet executed).

---

## ADR-002: Web Framework — Spring Boot 3.x

**Status**: Accepted (human candidate, 2026-10-18)

**Context**: Needs: REST for two planes, servlet filter for rate limiting, transactional persistence coupling for audit, test slices, actuator health.

**Decision Drivers**: C-002, constitution IV (test categories), VII (layering), C-006.

**Options Considered**:
- Spring Boot 3.x — pros: one-stop (web, data, transactions, actuator, test slices), candidate fluency; cons: startup weight, auto-configuration opacity; risks: auto-config bypassing explicit boundaries — mitigated by own-servlet-filter policy for Bucket4j and ArchUnit layer rules.
- Quarkus 3.x — pros: fast startup, native option; cons: shallower fluency, custom auth path; risks: timebox.
- Javalin/plain JDK — pros: minimal, explicit; cons: hand-built plumbing for transactions, security, health; risks: reliability features become custom code (constitution VIII).

**Decision**: Spring Boot 3.x (Web MVC, Data JPA, Actuator; no auto-configured rate limiter).

**Rationale**: Transactional audit coupling (ADR-005) and test-slice coverage of all constitution-IV categories are native fits; Actuator covers FR-010; fluency reduces risk.

**Consequences**: Jakarta EE 10 baseline; ArchUnit rules enforce domain-not-Spring and plane boundaries.

**Reversibility**: Moderate — controllers and repos behind interfaces; domain layer framework-free by rule.

**Traceability**: C-002, constitution IV/VII; plan §2, §10, §12; research.md row 2.

**Validation**: Contract and integration suites run under Spring Boot Test (planned).

---

## ADR-003: Persistence — H2 File Mode Behind Repository Interfaces

**Status**: Accepted (human candidate, 2026-10-18)

**Context**: Single-tenant demonstration service (A-001), zero-install local run, but durable state required for interruption/resumption (PVT-006) and audit retention (A-005).

**Decision Drivers**: C-006, PVT-006, FR-048, A-001.

**Options Considered**:
- H2 file mode — pros: embedded, file-durable, MVCC concurrency (MVStore) for PVT-003, in-SQL unique constraints for code/request-identity uniqueness (FR-016); cons: no network deployment; risks: none for scope.
- H2 in-memory — pros: fastest; cons: loses PVT-006 durability; risks: violates FR-028.
- SQLite via JDBC — pros: single file; cons: single-writer risk under PVT-003; risks: contention.
- PostgreSQL — pros: production path; cons: external dependency vs A-004, install/ops cost inside timebox, no added requirement coverage; risks: timebox.

**Decision**: H2 file mode, accessed only through repository interfaces; uniqueness enforced by DB constraints (candidate-approved addition).

**Rationale**: Zero-install durability for kill-and-restart; MVCC supports the PVT-003 concurrent-creation race; DB constraints make uniqueness provable rather than best-effort.

**Consequences**: PostgreSQL stays a documented swap path with a portable persistence test suite (disclosed); no cross-instance deployment (disclosed).

**Reversibility**: High — repository interfaces isolate the dialect; schema is portable.

**Traceability**: A-001, FR-016, FR-028, PVT-003/PVT-006; plan §2, §12, data-model.md; research.md row 3.

**Validation**: Persistence test category and PVT-003/PVT-006 suites (planned).

---

## ADR-004: Run Model — Single Fat Jar, Single Maven Module

**Status**: Accepted (human candidate, 2026-10-18)

**Context**: One process hosts both planes; the system runs locally for demonstration with external network dependencies minimized (A-004); reviewer starts it with one command (US-005).

**Decision Drivers**: C-002 (locally runnable prototype), A-004, C-006, US-005, SC-006.

**Options Considered**:
- Single fat jar, single module — pros: one build, one run command, simplest reviewer setup; kill-and-restart is the cleanest PVT-006 interruption/resumption demonstration; cons: no module boundaries enforced by build; risks: boundary drift — mitigated by ArchUnit plane rules.
- Two processes — pros: forced contract between planes; cons: inter-process contract, doubled ops surface; risks: timebox.
- Docker Compose — pros: reproducible environment; cons: mandatory Docker dependency vs A-004; risks: reviewer friction.

**Decision**: Single Spring Boot fat jar, single Maven module; plane boundaries enforced by ArchUnit, not module splits.

**Rationale**: Fastest path to a reviewer-executable artifact; single process makes kill-and-restart the PVT-006 demonstration; boundaries guaranteed by tests rather than build topology.

**Consequences**: Single process failure domain (disclosed, acceptable); package structure `com.agenticengineering.urlshortener.{shared,shortener,orchestration}` is load-bearing.

**Reversibility**: Medium — module boundaries are the seam if a split is later needed.

**Traceability**: C-002, A-004, US-005, SC-006; plan §3 (Project Structure), §12; research.md row 5.

**Validation**: `java -jar` boots both planes; ArchUnit suite passes; kill-restart test (planned).

---

## ADR-005: Workflow State and Audit — Relational Tables with Transactional Hash-Chained Audit

**Status**: Accepted (human candidate, 2026-10-18; scope of this record explicitly covers the hash-chain audit model)

**Context**: Constitution II requires persisted workflow state, cross-stage context, artifact provenance, decision lineage; FR-048 requires tamper-evident, append-only audit with integrity verification; PVT-007 requires 100% retrievable transitions.

**Decision Drivers**: Constitution II/IX/X, FR-023, FR-030, FR-048, PVT-006/PVT-007, A-011.

**Options Considered**:
- Relational tables + transactional hash chain (chosen) — pros: state and audit commit atomically (an event and its hash appear together or not at all); chain verification is a walk; cons: chain re-verification cost on append; risks: none material at prototype scale.
- Event-sourced journal with replay/snapshots — pros: elegant lineage; cons: machinery exceeds state size; risks: query cost for run detail.
- Per-run JSON files — pros: simplest; cons: no transactional coupling, no queryability; risks: PVT-007.
- Embedded workflow engine — pros: mature; cons: not used — the assignment requires designing and implementing the orchestration layer, and a framework would hide the governance logic.

**Decision**: Workflow state as relational tables in the shared H2 database (WORKFLOW_RUN with run_revision, STAGE_NODE states, stage-graph edges in STAGE_EDGE, task-level dependencies in TASK_DEPENDENCY); audit events as an append-only table where each event stores a hash over (prev-hash, event fields); event append and the state change it records commit in one transaction; no update/delete operations exposed (FR-048); chain verification walks the sequence and detects altered/removed/reordered events (hash-mismatch, link-broken, sequence-gap, truncation); verification is re-run at N14 after terminal appends; direct-storage tampering is detectable, not prevented (A-011, disclosed).

**Rationale**: Single-transaction coupling is the only design where state and its evidence cannot diverge; relational form makes PVT-007 reconstruction a query.

**Consequences**: Audit writes share the domain transaction boundary; chain head recorded per run; verification endpoint (FR-048).

**Reversibility**: Moderate — chain format is durable once events exist; schema behind repositories.

**Traceability**: Constitution II/IX/X, FR-023, FR-030, FR-048, PVT-006/PVT-007, A-005, A-011; plan §4, §7, data-model.md (PLATFORM_AUDIT_HEAD, AUDIT_EVENT, STAGE_EDGE, TASK_DEPENDENCY).

**Validation**: Tamper-detection test (altered/removed/reordered event → verification fails at identified position); N14 re-verification test (planned).

---

## ADR-006: Short-Code Generation, Collision Handling, and Request Idempotency

**Status**: Accepted (human candidate, 2026-10-18; includes the approved spec-delta 409 conflict response, the approved 200-replay/201-new distinction, and the approved reserved-route-word exclusion) Amendments (Idempotency-Key header, replay after expiry returns 200, base-62) re-accepted 2026-10-21 (T124, specs/001-agentic-url-shortener/approvals/2026-10-21-t014-t124.md).

**Context**: FR-016 uniqueness under 100 concurrent creations (PVT-003); FR-004 never-reassign; FR-007 idempotent retries of one logical request (AMB-2: independent requests to the same URL create separate links); short codes are permanent public identifiers.

**Decision Drivers**: FR-004, FR-007, FR-016, PVT-003, NFR-007.

**Options Considered**:
- Random base-62 generation + DB unique constraint + bounded regeneration on collision (chosen) — pros: no coordination, no sequential guessability, collision handled by constraint; cons: collision retries (rare at this scale); risks: none material.
- Sequential counter + base-62 — pros: zero collisions; cons: guessable codes, counter is a hot row under PVT-003; risks: contention.
- Hash-derived from target — pros: deterministic; cons: same-URL requests would collide (violates AMB-2), code length blows up on long URLs; risks: violates FR-007 independence.

**Decision**: Base-62 alphabet (adopted by human decision 2026-10-19; re-accepted 2026-10-21 at T124), minimum length 4, random generation; uniqueness enforced by a DB unique constraint; on collision the generation is retried with a fresh code (bounded); the short-code generator never emits the reserved top-level route words `api` and `actuator` (a colliding generation is rejected and regenerated; the minimum length of 4 already excludes `api`, and the exclusion additionally protects the `actuator` route). Idempotency: the client supplies a stable `Idempotency-Key` header; the key is bound to the normalized payload; same key + same payload returns the existing link (HTTP 200); same key + different payload returns 409 with no overwrite (approved spec delta); no key or malformed key is rejected (400, no code allocated); independent requests to the same URL each create new links.

**Rationale**: DB-constraint uniqueness makes the PVT-003 race provable rather than best-effort; random codes avoid enumeration; reserved-word exclusion guarantees no short code can collide with a first-level route.

**Consequences**: Reserved-word exclusion slightly reduces the code space (disclosed); the idempotency binding is retained as long as the link row exists (no purge endpoint; links are never deleted), so a replay after the link's expiry returns the original link unchanged with HTTP 200 (human decision 2026-10-19).

**Reversibility**: Low for issued codes — FR-004 makes allocation permanent; high for the generation algorithm itself pre-release.

**Traceability**: FR-004, FR-007, FR-016, AMB-2, PVT-003, SCN-A; plan §2, contracts/openapi.yaml (`/api/v1/links`), data-model.md (SHORT_LINK).

**Validation**: PVT-003 suite; idempotency tests (replay 200, conflict 409, independent same-URL links); reserved-word generation test (planned).

---

## ADR-007: Orchestration Model — Explicit Dependency Graph With Governed Dynamic Replanning

**Status**: Accepted (human candidate, 2026-10-18)

**Context**: Constitution II (non-waivable): more than sequential chaining; sequential and parallel paths, synchronization, branching, dynamic replanning, interruption, resumption; FR-029; D-006 (triggers and recovery details resolved in plan).

**Decision Drivers**: Constitution II, FR-025..FR-029, FR-046, D-006, PVT-004/PVT-006/PVT-007.

**Options Considered**:
- Hand-built dependency-graph tables (chosen) — pros: fully inspectable, queryable lineage (PVT-007); cons: transition engine is custom code; risks: engine bugs — mitigated by transition test category.
- Enumerated state machine per run (fixed topology) — pros: simpler; cons: cannot represent parallel paths or replanning without rework; risks: violates constitution II.
- Embedded workflow engine — pros: mature; cons: not used — the assignment requires designing and implementing the orchestration layer, and a framework would hide the governance logic.

**Decision**: Orchestration is an explicit directed graph persisted relationally: nodes (N01–N14) with states, stage-graph edges in STAGE_EDGE (sequencing, parallel fan-out, synchronization joins), task-level dependencies in TASK_DEPENDENCY (task level only), a simple bounded thread pool for the parallel executors (disclosed minimal implementation; upgrade path noted). Replanning (FR-029, FR-046, D-006): a replan event mutates the graph downstream of the change point, increments WORKFLOW_RUN.run_revision, queues re-validation of affected artifacts/evidence/approvals, and takes effect only when accepted by a human approver whose key identity differs from the replan requester; rejection leaves the previous revision in force.

**Rationale**: Graph tables make every constitution-II capability inspectable and testable; revision counter keeps pre/post-replan lineage unambiguous; distinct-approver rule extends FR-046 to graph mutation.

**Consequences**: Transition engine is in-scope custom code with its own test category; graph schema changes require change control (C-005).

**Reversibility**: Moderate — node/edge schema is change-controlled; revisions preserve history.

**Traceability**: Constitution II, FR-025..FR-029, FR-046, D-006, PVT-004/PVT-006/PVT-007; plan §3, §4, data-model.md (WORKFLOW_RUN, STAGE_EDGE, TASK_DEPENDENCY).

**Validation**: Transition tests (every legal/illegal transition), replanning acceptance/rejection tests, PVT-004/PVT-006 (planned).

---

## ADR-008: Human Approval and Authentication Model — Role-Scoped API Keys With Gate-Role Mapping

**Status**: Accepted (human candidate, 2026-10-18; gate-role mapping corrected per review before acceptance; D-004 names this ADR as its decision point)

**Context**: FR-045 role-scoped keys (engineer, approver, release-owner, reviewer); FR-046 self-approval prohibition; A-009 keys are a local-prototype mechanism; D-004 (key storage, hashing, block duration) resolved in plan.

**Decision Drivers**: FR-045, FR-046, FR-047, A-008c/d, A-009, D-004, NFR-001, constitution III.

**Options Considered**:
- Role-scoped static API keys, SHA-256 stored, constant-time compare (chosen) — pros: zero dependencies, auditable, sufficient for a single-instance prototype; cons: no rotation/expiry mechanism in scope (limitation); risks: key leakage — mitigated by gitignored raw keys, hashed storage, key-ID-only audit records.
- Spring Security user store — pros: standard; cons: session/form machinery irrelevant to an API prototype; risks: timebox.
- OAuth2/JWT — pros: production-real; cons: an identity provider for a local prototype is out of scope; risks: contradicts A-009's "not production identity".

**Decision**: Four role-scoped keys (ENGINEER, APPROVER, RELEASE_OWNER, REVIEWER); raw values live only in a local gitignored source labeled demo-only and shell exports; SHA-256 hashes in the key store; constant-time comparison; audit records carry key ID only; failed-auth rate limiting per A-008d with configurable block. Gate-role mapping (plan §5 numbering): G1 unresolved ambiguity, G2 architecture approval, G3 security-sensitive action, G4 destructive/irreversible action, and replan acceptance — APPROVER; G5 constitutional/policy exception, G6 material risk acceptance, G7 release readiness, G8 final submission — RELEASE_OWNER; REVIEWER is read-and-verify only. Every approval at every gate additionally requires an approver key identity distinct from the requester's (FR-046); self-approval and agent approval return 403, leave the gate pending, and are audited. Approval windows: 5 minutes, one escalation re-issue with an equal second window, then safe-stop (PVT-005).

**Rationale**: Minimal mechanism that fully satisfies FR-045/FR-046; the mapping gives each constitutional gate exactly one accountable human role and makes self-approval structurally deniable in tests.

**Consequences**: The human candidate must hold (or generate) at least two distinct key identities to exercise approvals; no production identity claims (A-009 disclosed).

**Reversibility**: High — swap the key store for a real identity provider behind the same role checks.

**Traceability**: FR-045, FR-046, FR-047, A-008c/d, A-009, D-004, PVT-005; plan §5, §8, contracts/openapi.yaml (x-required-roles), quickstart.md.

**Validation**: 401/403 authz tests, self-approval 403 test, timeout/escalation/safe-stop tests (PVT-005) (planned).

---

## ADR-009: Failure Semantics — Bounded Retry, Fallback, Safe-Stop, and Rollback vs Compensation

**Status**: Accepted (human candidate, 2026-10-18; D-001 and the rollback/compensation table resolved in plan with candidate additions)

**Context**: Constitution VIII (non-waivable): classified failures, bounded retries, idempotency, defined fallback and safe-stop, rollback distinguished from compensation, deterministic terminal outcomes, resumability.

**Decision Drivers**: Constitution VIII, D-001, D-005, FR-025..FR-028, PVT-004/PVT-005/PVT-006/PVT-008.

**Options Considered**:
- Per-operation classification with policy-driven handling (chosen) — pros: each operation's behavior is explicit, testable, and auditable; cons: classification table must be maintained; risks: misclassification — mitigated by default-to-permanent rule.
- Uniform retry-everything — pros: simple; cons: violates constitution VIII (permanent failures retried, unbounded effects); risks: non-deterministic terminals.
- Workflow-engine compensation frameworks — pros: mature; cons: not used — the assignment requires designing and implementing the orchestration layer, and a framework would hide the governance logic.

**Decision**: Every external-effect operation is classified transient or permanent (D-001; unknown defaults to permanent). Retries: bounded (≤3), exponential backoff, terminal within 60 s (PVT-004). Fallbacks defined per operation; safe-stop conditions explicit (unclassified failure; approval timeout through escalation). A failed mandatory policy check is not a safe-stop: it takes the NOT READY release-readiness path (N12 FAIL → NOT READY persisted → N14 reporting only; N13 not taken), blocking release readiness (FR-037). Rollback vs compensation (plan table): provably reversible operations roll back (demonstrable case: uncommitted transaction rows); irreversible after-effects get compensation (demonstrable case: N08 committed code — compensation is a revert commit, classified compensation not rollback). Resumption (PVT-006): durable state plus operation-outcome reconciliation with no duplicate effects, including the after-effect/before-completion-record case. MTTR per constitution definition (recovered events only; unrecovered reported separately; governance wait excluded and reported separately).

**Rationale**: Classification-first design is the only way to make PVT-008 metrics meaningful and constitution-VIII compliance inspectable; the uncommitted-transaction case keeps rollback demonstrable rather than asserted; the policy-FAIL path keeps release-readiness blocking visible in the demonstration.

**Consequences**: FAILURE_EVENT and OPERATION_RECORD tables carry classification and timing; every failure path has a test; the forced-FAIL demonstration (POL-DEP-01, simulated undeclared dependency) exercises the NOT READY path.

**Reversibility**: High — policies are data; the classification table is change-controlled.

**Traceability**: Constitution VIII, D-001, D-005, FR-025..FR-028, FR-031, FR-037, PVT-004/PVT-005/PVT-006/PVT-008; plan §6, data-model.md (OPERATION_RECORD, FAILURE_EVENT, rollback/compensation table).

**Validation**: Retry-exhaustion, fallback, safe-stop, kill-and-restart (PVT-006), forced-FAIL NOT READY demonstration, and MTTR computation tests (planned).

---

## ADR Inventory (post-acceptance)

| ADR | Title | Status |
|---|---|---|
| ADR-001 | Java 21 LTS | Accepted (human candidate, 2026-10-18) |
| ADR-002 | Spring Boot 3.x | Accepted (human candidate, 2026-10-18) |
| ADR-003 | H2 file mode behind repositories | Accepted (human candidate, 2026-10-18) |
| ADR-004 | Single fat jar, single module | Accepted (human candidate, 2026-10-18) |
| ADR-005 | Relational workflow state + transactional hash-chained audit | Accepted (human candidate, 2026-10-18; hash-chain scope explicit) |
| ADR-006 | Short-code generation, collision, idempotency | Accepted (human candidate, 2026-10-18; reserved-word exclusion approved) |
| ADR-007 | Dependency-graph orchestration + governed replanning | Accepted (human candidate, 2026-10-18) |
| ADR-008 | Role-scoped keys + gate-role mapping | Accepted (human candidate, 2026-10-18; mapping corrected per review) |
| ADR-009 | Failure semantics: retry/fallback/safe-stop, rollback vs compensation | Accepted (human candidate, 2026-10-18) |

Deferrals accepted by the human candidate 2026-10-18: testing strategy (implementation decision, plan §10), analytics consistency (resolved by Clarification Q1: A / AMB-1; accepted-limitation record at G6), version pinning (engineering-baseline task), production upgrade paths (disclosed limitations).

Corrections applied before acceptance: (1) ADR-008 gate mapping aligned to plan §5 numbering (G1–G4 + replan acceptance = APPROVER; G5–G8 = RELEASE_OWNER; REVIEWER read/verify only; approver distinct from requester at every gate; ENGINEER-answers-clarification removed); (2) ADR-005/007/009 framework options rephrased (assignment requires designing and implementing the orchestration layer; a framework would hide the governance logic); (3) ADR-009 failed mandatory policy check moved to the NOT READY path and traceability corrected to FR-025..FR-028; (4) ADR-005/007 node-graph edges = STAGE_EDGE, TASK_DEPENDENCY = task level only; (5) ADR-003 single-writer con removed (H2 MVStore: MVCC), A-010 → A-001, C-003 dropped; ADR-004 C-004 → C-002/A-004; (6) ADR-001 persona phrasing removed and rejected alternatives aligned with plan §12 / research.md (ADR-001: TypeScript/Node 22, Python 3.12; ADR-002: Quarkus 3.x, Javalin/plain JDK).
