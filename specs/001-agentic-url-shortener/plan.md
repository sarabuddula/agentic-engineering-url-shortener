# Implementation Plan: Agentic Software Engineering System — URL Shortener

**Branch**: `001-agentic-url-shortener` | **Date**: 2026-10-18 | **Spec**: `specs/001-agentic-url-shortener/spec.md`

**Input**: Feature specification from `specs/001-agentic-url-shortener/spec.md` (Approved — Human Gates 2 and 3 passed 2026-10-18)

**Technology decisions**: Selected by the human candidate 2026-10-18; ADR acceptance pending Human Gate 4 (ADR-001 to ADR-005, drafted separately for the ADR gate). This plan itself is **Proposed** until the plan/ADR human gate passes.

## Summary

A production-oriented, locally runnable prototype delivered as a single Spring Boot fat jar on Java 21, divided into two strictly separated planes: an **application plane** (URL shortener: creation, short-code generation, redirect resolution, analytics, expiration, validation, health — US-001) and a **control plane** (a stateful orchestration engine executing an explicit dependency graph of SDLC stages with entry/exit gates, sequential and parallel paths with synchronization, conditional branching, human approval checkpoints, bounded retries, fallback, rollback/compensation, safe-stop and resumption, dynamic replanning, versioned policy guardrails, and a hash-chained append-only audit trail — US-002 to US-005). Workflow state, approvals, and audit events are persisted as relational tables in one H2 file database; each state transition commits atomically with its audit event, and resumption reconciles operation outcomes via durable operation identities and completion records. The system is demonstrated through scenarios SCN-A (greenfield), SCN-B (brownfield), and SCN-C (ambiguous requirement), producing traceable, reviewer-verifiable evidence and a human-owned release-readiness decision (READY / READY WITH ACCEPTED LIMITATIONS / NOT READY).

## Technical Context

**Language/Version**: Java 21 (LTS, e.g. Temurin) — ADR-001. Rejected: TypeScript/Node 22, Python 3.12 + FastAPI (see ADR-001).

**Primary Dependencies** (version pins recorded in `pom.xml` and the dependency/license policy inventory at the engineering-baseline task, per Constitution VI):
- Spring Boot 3.x (Web MVC, Spring Data JPA, Actuator) — ADR-002; domain layer framework-free (NFR-004), enforced by ArchUnit.
- **Bucket4j: `com.bucket4j:bucket4j_jdk17-core` 8.21.0 (Apache-2.0)** — version to be verified and pinned at the engineering-baseline task — approved 2026-10-18 for FR-047 / A-008a-d. Implemented via our own servlet filter (no starter auto-configuration), in-memory buckets for the single-instance prototype, bounded bucket map with a max-entries ceiling; eviction removes only entries whose removal cannot restore restricted capacity (long-idle identities); when capacity is exhausted, new identities are rejected rather than discarding active limits — a capacity-pressure test is required (§8). Limits reset on restart and are per-instance; a shared backend would be the production path (out of scope). Failed-auth temporary block retained as a separate mechanism (D-004, resolved — §8).
- JUnit 5, AssertJ, Mockito, REST Assured, ArchUnit — testing stack (implementation decision, below).
- H2 2.x (file mode) — ADR-003.
- Build: Maven, single module unless implementation evidence justifies more (ADR-004).
- **Approved 2026-10-18** (human candidate, conditional on license/version verification; verified 2026-10-18 by direct retrieval: version 2.44.8 on Maven Central; Apache-2.0 per the MVNrepository listing, which notes the Maven Central page does not state the license): `com.atlassian.oai:swagger-request-validator-restassured:2.44.8` for executable OpenAPI contract validation in API contract tests.

**Storage**: H2 in file mode — one shared database for both planes, behind repository interfaces so the domain stays storage-independent (NFR-004, Constitution VII). Uniqueness for short codes and request identity is enforced by database constraints, not application checks (PVT-003 concurrency). Disclosed: H2 isolation does not guarantee serializable write behavior — concurrency correctness relies on constraint enforcement plus optimistic locking; the PostgreSQL swap path is documented but not guaranteed identical (partial compatibility mode). A persistence test suite is included that can run against PostgreSQL later. Rejected: in-memory-only, SQLite, PostgreSQL-as-default (ADR-003).

**Testing**: JUnit 5 + Spring Boot Test + AssertJ + Mockito + REST Assured + ArchUnit (implementation decision, not an ADR). NFR-009 category mapping with concrete packages (root `com.agenticengineering.urlshortener`):

| Test category | Tool | Test package |
|---|---|---|
| Unit (domain) | JUnit 5 + AssertJ (container-free) | `…urlshortener.shortener.domain` (co-located) / `tests.unit` |
| Integration | Spring Boot Test slices (`@DataJpaTest`, context tests) | `…tests.integration` |
| API contract | REST Assured + OpenAPI validator (proposed dep, above) validating status, headers, and bodies against `contracts/openapi.yaml` | `…tests.contract` |
| Orchestration transition | JUnit 5 (state-machine tests, container-free) | `…tests.orchestration` |
| Reliability | JUnit 5 + fault-injection harness (retry/timeout/safe-stop/resume) | `…tests.reliability` |
| Security | REST Assured (auth/rate-limit) + ArchUnit security rules (separate from functional tests) | `…tests.security` |
| End-to-end | `@SpringBootTest` full scenario runs (SCN-A/B/C) | `…tests.e2e` |

Domain and orchestration-transition tests are container-free for fast red-green-refactor loops (C-003). ArchUnit rules and all categories execute under `mvn verify`. Rate-limit tests cover 429 with retry hint, no short code allocated, audited event, and configurable low limits for test runs.

**Target Platform**: Local, single JVM (Java 21), single Spring Boot fat jar; kill-and-restart of the JVM is the designated PVT-006 interruption-and-resumption demonstration (ADR-004). Rejected: two-process deployment, Docker Compose (optional Dockerfile = backlog only).

**Project Type**: Web service, one process, two planes in separate top-level packages with an explicit interface between them; ArchUnit rules fail the build on forbidden dependencies (see Project Structure).

**Performance Goals**: PVT-001 (≥ 99% resolution success for valid active links), PVT-002 (≥ 95% of redirects < 200 ms local), PVT-003 (100 concurrent creations all correct), PVT-004 (at most 3 retry attempts per stage, deterministic terminal outcome within 60 s on the automated failure path).

**Constraints**: 2–3 day timebox (C-006); local-only, external runtime dependencies minimized (A-004); no fabricated evidence, demonstration metrics distinguished from production measurements (C-004); red-green-refactor TDD (C-003).

**Scale/Scope**: Single instance, single tenant (A-001), demonstration-grade analytics (A-003), role-scoped keys as a local-prototype mechanism (A-009).

## Constitution Check

*GATE: must pass before Phase 0 research; re-checked after Phase 1 design. The table below is an **initial design-conformance assessment** of this proposed plan, not a completed gate. The technology selections were approved by the human candidate on 2026-10-18; this plan and its ADRs are not yet approved — ADR acceptance and the final constitution check are pending Human Gate 4.*

| Principle | Assessment | Basis |
|---|---|---|
| I. Specification before implementation | Conforms (design) | Approved spec (Human Gates 2–3); every plan element cites FR/NFR/PVT IDs; no implementation during planning |
| II. Explicit agentic orchestration | Conforms (design) | §3: persisted dependency graph, node states, gates, parallel paths with synchronization, durable state and lineage |
| III. Human governance | Conforms (design) | §5: approval gates, escalation + safe-stop on silence, self-approval denial (FR-046), human-owned release decision |
| IV. Test-driven engineering | Conforms (design) | §10: red-green-refactor, all seven NFR-009 categories with tools and packages |
| V. Security and privacy by design | Conforms (design) | §8: input validation, scheme restriction, rate limiting, secrets out of logs, dependency/secret scanning at release readiness |
| VI. Compliance and change control | Conforms (design) | §9: versioned policy set per run, four-valued outcomes, exception workflow, release-blocking behavior |
| VII. Architecture and maintainability | Conforms (design) | §2, ADR-001 to ADR-005, ArchUnit-enforced plane/layer separation, explicit interfaces |
| VIII. Reliability and recovery | Conforms (design) | §6: failure classification, bounded retries (PVT-004), fallback, rollback vs compensation, safe-stop, resumption with reconciliation |
| IX. Observability and auditability | Conforms (design) | §7: run correlation ID, hash-chained audit events with verification, demonstration metrics |
| X. Traceability and repository integrity | Conforms (design) | §13: requirement-to-evidence chain; small coherent commits per task group |
| XI. Evidence-based completion | Conforms (design) | §10/§14 and the FR-044 completion gate enforced by the engine |

No violations identified at this stage. If later parts introduce any, they must be recorded in Complexity Tracking with justification, or the plan is revised before the gate. Final gate decision: **pending** (re-check after Phase 1 design and full-plan assembly).

## Project Structure

Single Maven module; the two planes are separate top-level packages; the cross-plane contract is the `shared` package (a package boundary within the single module, per ADR-004).

```text
src/main/java/com/agenticengineering/urlshortener/
├── shared/                  # explicit cross-plane contract: interfaces, DTOs, IDs, events
│   └── (no framework imports; pure Java)
├── shortener/               # APPLICATION plane
│   ├── api/                 # REST controllers, error mapping (Spring)
│   ├── domain/              # framework-free domain logic
│   ├── persistence/         # JPA entities + repository implementations
│   └── analytics/           # capture + reporting
└── orchestration/           # CONTROL plane
    ├── api/                 # control-plane REST endpoints
    ├── engine/              # graph scheduler, node runtime, executors
    ├── policy/              # versioned policy evaluation
    ├── approval/            # approval lifecycle
    ├── audit/               # hash chain + verifier
    └── persistence/         # control-plane JPA repositories

src/test/java/com/agenticengineering/urlshortener/
├── shortener/domain/        # container-free domain unit tests
├── tests/unit/              # remaining unit tests
├── tests/integration/
├── tests/contract/
├── tests/orchestration/
├── tests/reliability/
├── tests/security/
└── tests/e2e/

specs/001-agentic-url-shortener/
├── plan.md                  # this file
├── research.md               # Phase 0 output
├── data-model.md             # Phase 1 output
├── quickstart.md             # Phase 1 output
├── contracts/                # Phase 1 output
└── tasks.md                  # later task-generation stage (NOT created by planning)
```

**Allowed dependency directions (ArchUnit-enforced, build fails on violation):**
- `shortener.domain` and `shared` → no Spring, no JPA, no framework imports.
- `shortener.*` → `shortener.*`, `shared`. **Never** `orchestration.*`.
- `orchestration.*` → `orchestration.*`, `shared`, and `shortener.domain` **only via interfaces declared in `shared`**.
- `shortener.api` / `orchestration.api` → their own plane + `shared`; controllers stay thin adapters.

**Structure Decision**: single-module Maven layout with package-level plane separation. The plane and layer rules above make the boundaries testable; a multi-module split is deferred unless implementation evidence justifies it (would require a change approval under C-005).

## Plan — Required Coverage

### 1. System Context

**System boundary.** One JVM process exposing HTTP on localhost: an application-plane API (public redirect resolution; open link creation per A-002) and a control-plane API (role-scoped key required, FR-045). Storage is embedded (H2 file). One bounded external runtime dependency: creation-time DNS resolution, accessed through an explicit resolver interface (Constitution VII) so tests inject a stub resolver (PVT-003; A-004 trust boundary; §8). The resolver has a **mode switch**: `live` (real DNS) or `fixed-local` (fixed local resolution table, no network — the demonstration default, making scenario runs offline-safe and deterministic).

**Actors.**
- **API consumer** — public, unauthenticated creation (A-002) and resolution; subject to per-IP rate limits (A-008a/b).
- **Human candidate** — sole human authority (architecture owner, decision, approval, reviewer, final quality). Holds role-scoped keys (engineer, approver, release owner, reviewer). Self-approval prohibited (FR-046); distinct key identities used for requester vs approver (A-009).
- **Agent actors** — workflow stage executors with bounded, observable, auditable autonomy; can never approve (FR-046, NFR-011).
- **Assessment reviewer** — read-only reconstruction from repository and audit records (US-005).

**External dependencies.** One bounded runtime dependency: creation-time DNS resolution for target-address validation (local resolver, bounded lookup timeout, result cached; unresolved or unsafe results are rejected — §8). Build-time only: Maven Central. AI assistance is a development-time practice of the human candidate, not a runtime dependency of the system (see Agent Executor Model, §3). Documented as a trust boundary per A-004.

**Trust boundaries.**
1. Public client → application plane: all input validated and normalized (FR-002, FR-013), rate-limited (A-008).
2. Key-authenticated client → control plane: 401/403 with audit (FR-045/046), per-key rate limit (A-008c), failed-auth per-IP limit (A-008d).
3. Application plane ↔ control plane: the `shared` package interface only; ArchUnit forbids `shortener` importing orchestration internals.
4. Process ↔ storage: direct storage access can tamper with the audit chain — hash-chaining detects but does not prevent this (A-011, disclosed).

**Major runtime components.** API delivery; URL-shortener domain; short-code generator; URL validator; redirect resolver; persistence repositories; analytics capture; expiration evaluator; health/readiness; orchestration engine (graph scheduler + node executors); approval service; policy evaluator; audit chain and verifier; telemetry/metrics; configuration.

**Data ownership.** Application plane owns `ShortLink` and `RedirectEvent`. Control plane owns `WorkflowRun`, `StageNode` (+ edges), `Approval`, `AuditEvent`, `PolicyEvaluation`, `ReplanningEvent`, `EngineeringSummary`. Cross-plane references use identifiers only.

**Plane responsibilities.** Application plane: the URL shortener service (US-001, FR-001 to FR-016). Control plane: orchestration, approvals, policy enforcement, audit, reliability measurements, completion gate (FR-044), release-readiness recommendation (FR-037), scenarios SCN-A/B/C (FR-040 to FR-043), final summary (FR-038).

### 2. URL Shortener Architecture

**Components.**

| Component | Responsibility | Requirement IDs |
|---|---|---|
| API delivery | REST controllers, request/response mapping, defined error responses | FR-001, FR-002, FR-005, FR-006, FR-014 |
| Domain logic | Create/resolve/expire decisions, idempotency, framework-free | FR-007, FR-008, FR-011 |
| Short-code generation | Random base-62 candidates (proposal); uniqueness enforced by DB constraint; collision → regenerate within bounded retries; codes never reassigned | FR-004, FR-016, PVT-004 |
| URL validation | Scheme allowlist HTTP/HTTPS (A-007); internal/local address rejection (loopback, private, link-local and the full rule set — D-003, resolved in §8); size limit; whitespace stripping and canonical form | FR-002, FR-003, FR-013, NFR-001 |
| Redirect resolution | Active → 302 to target (proposal); expired → defined expired-link response, 410 proposed; unknown/deleted → 404 with no redirect; expiration race resolves to expired behavior | FR-005, FR-006, FR-014 |
| Persistence | JPA repositories; unique constraints on short code and request identity; optimistic locking | FR-007, FR-016, PVT-003 |
| Analytics | Synchronous capture in the redirect path (attempt outcome, count, timestamp); bounded, quantified loss on capture failure; no personal information | FR-012, FR-015, A-003 |
| Expiration | Explicit expiry or default TTL 7 days (A-006); lazy evaluation at resolution | FR-011 |
| Health/readiness | Actuator health plus custom dependency check (storage reachable) | FR-010 |
| Telemetry | Demonstration metrics (success rate, latency, retry/rollback counts) | FR-031 |
| Configuration | Externalized limits, TTLs, timeouts, key material via configuration properties | A-008, NFR-001 |

**Concurrency and idempotency mechanics (design).** Creation runs in a transaction with a unique constraint on request identity: concurrent retries sharing one identity race on the constraint; the loser reads and returns the existing link (same short code, same result, original effective expiration preserved). A replay carrying the same request identity but a different normalized payload returns a defined conflict response (409, approved 2026-10-18) and is audited — no silent overwrite. Independently issued requests (distinct identities) each create new links (FR-007). Short-code collisions are handled by the separate short-code unique constraint with bounded regeneration. PVT-003's concurrency tests exercise both races distinctly.

**Spec deltas (C-005).** The 409 conflict response for a request-identity replay carrying a different normalized payload is a behavior **not present in the approved specification**, recorded as a spec delta — **approved by the human candidate 2026-10-18** (it follows from the FR-007 idempotency design); treated as binding from that date. Items that remain proposals for contract review (not spec deltas): 302 redirect status, 410 expired-link response, base-62 code alphabet.

**Analytics failure observability (FR-015, design proposal for Phase 1).** When synchronous capture fails but resolution succeeds, the failure is recorded best-effort and an in-memory unrecorded-event counter is incremented; a periodic durable checkpoint writes the counter to a loss-report record so the loss stays quantified and reportable. Disclosed limitation: a process crash between failure and checkpoint loses the count (bounded by the checkpoint interval); checkpoint interval is configurable.

**API contracts** (full schemas in `contracts/`, Phase 1; OpenAPI 3.0 proposed, validated by contract tests):
- Application plane: `POST /api/v1/links` (create; carries request identity for idempotency), `GET /{code}` (redirect), `GET /api/v1/analytics/summary` (basis: FR-012 reporting), `GET /actuator/health`.
- Control plane: `POST /api/v1/orchestrator/runs` (ingest requirement), `GET /api/v1/orchestrator/runs/{id}` and `/history` and `/verify` (audit-chain verification), `POST /api/v1/orchestrator/runs/{id}/approvals/{approvalId}` (approve/reject), `POST …/resume`, `POST …/replan`, `GET …/release-readiness`, `GET /api/v1/metrics/demo` (reviewer role key).
- A per-link metadata endpoint is **not** included (no requirement basis; recorded as backlog).

**Versioning and compatibility rules.** APIs are versioned (`/api/v1`); within v1 only additive changes are permitted. Every API or schema change record identifies ownership, version impact, backward-compatibility impact, affected consumers, tests, documentation, rollout/migration steps, and required change approval (change control, §9). Representative examples are included in `contracts/`.

### 3. Agentic Orchestration Architecture

**Model.** An explicit dependency graph persisted as `StageNode` rows and edge rows. The engine is a scheduler: on each tick it promotes nodes whose predecessors are in a satisfied state and whose entry criteria hold to READY, dispatches READY nodes to a bounded worker pool (parallel paths, FR-043), and commits every state transition together with its audit event in one transaction. Optimistic locking (version column) on `WorkflowRun`/`StageNode` prevents duplicate state effects under concurrent ticks or resumption. Atomic commits guarantee state/audit consistency; **exactly-once side effects are achieved by durable execution discipline, not transactionality alone**: every side-effecting operation has a stable operation identity and a durable completion record; on resumption, operations without a completion record are reconciled (checked for effect) or the run safe-stops rather than blindly re-executing. The PVT-006 demonstration includes the hardest case: interruption *after an effect but before its completion record*.

**Agent executor model (explicit labeling per C-004).** Each node type has a registered executor: `StageExecutor.execute(NodeContext) → ExecutionResult` where the result carries artifacts, evidence, and an outcome (SUCCEEDED / FAILED-transient / FAILED-permanent / NEEDS_APPROVAL). Executors are bounded: they cannot approve (FR-046), cannot modify approved artifacts (attempts blocked and audited), and operate only on whitelisted operations (versioned artifact read/write, test-suite invocation, metrics query). Two executor classes, labeled:
- **Deterministic executors** — rule-based transformations with recorded inputs and outputs (requirement-quality heuristics, impact analysis over the actual codebase, decomposition from approved templates, policy evaluation, evidence assembly).
- **Human/AI-assisted artifact production** — where a stage's work product (design text, ADRs, code) is produced by the human candidate with AI assistance *outside* the runtime system and ingested as a versioned artifact with provenance (labeled AI-assisted, human-reviewed, per Constitution X). The runtime records execution and validation evidence; it does not fabricate authorship.

The runtime graph demonstrates governance for **ingested demonstration requirements** (SCN-A/B/C). The in-system gates (N07, N13) mirror but do **not** replace the constitution's separate lifecycle gates for building this prototype itself — architecture approval (Human Gate 4), checklist, task generation, analysis, pre-implementation review, and reviewer gates remain authoritative for this repository.

**Graph (SCN-A path; conditional nodes marked).**

```
N01 requirement-ingestion
  → N02 requirement-quality-check
    → [N03 clarification-gate]            (conditional: taken when material ambiguity detected — SCN-C, or mid-run in SCN-A)
    → [N04 impact-analysis]              (conditional: brownfield SCN-B / replanning)
  → N05 decomposition
  → N06 design
  → N07 adr-approval-gate                 (HUMAN)
  → N08 implementation                   (fan-out to parallel task executors → synchronization point)
  → { N09 testing ∥ N10 documentation }   (parallel paths, synchronization before N11)
  → N11 validation-evidence
  → N12 policy-evaluation
  → N13 release-readiness-gate            (HUMAN)
  → N14 engineering-summary              (terminal)
```

**Branch semantics.** Conditional branch selection is persisted on the run. A conditional node not taken ends in the recorded state NOT_APPLICABLE (with reason) — never silently "skipped" — so inactive branches are excluded without bypassing mandatory gates. When one path suspends (clarification, approval, safe-stop), only the affected path stops; independent parallel paths proceed to the synchronization point, which then waits for resolution of the stopped path (edge case, FR-024). N03 triggers on *any* detected material ambiguity, including mid-run ambiguity on the greenfield path (SCN-A scenario 4).

**State machines.**
- **Run**: RUNNING, AWAITING_APPROVAL, SUSPENDED_CLARIFICATION, SAFE_STOPPED, COMPLETED, TERMINATED (governed termination or terminal failure). All transitions audited (FR-030).
- **Node**: PENDING → READY → RUNNING → SUCCEEDED | FAILED | NOT_APPLICABLE. FAILED → RETRY_WAITING (transient only) → RUNNING; exhausted retries or permanent failure → the node's declared failure behavior (fallback, compensation, or safe-stop). A node requiring a human decision enters AWAITING_APPROVAL. **Prohibited transitions**: PENDING → RUNNING (entry criteria unchecked); AWAITING_APPROVAL → SUCCEEDED (no recorded approval); any transition out of a terminal state; NOT_APPLICABLE → any active state on the same run revision. Blocked progression attempts are audited (SC-004).
- **Approval**: PENDING → APPROVED | REJECTED | (timeout) ESCALATED — re-issued once with a second window of equal length, audited (PVT-005, CL-2) → (second timeout) the affected path enters SAFE_STOPPED. Approval timeouts and rejections are non-retryable (FR-025); rejection leads to bounded rework or governed termination, never automatic retry. Silence is never approval.

**Named failure policies (referenced by nodes; concrete per-stage definitions in Phase 1 data-model).**
- **FP-1 Retry**: transient failure only; at most 3 retry attempts after the initial attempt (PVT-004); exponential backoff; the 60 s deterministic-terminal bound applies to the automated failure path and is separate from human approval windows (PVT-005).
- **FP-2 Fallback**: on primary-path failure within bounds, execute the alternative recorded in advance for that node, or transition to the node's failure behavior (FR-026).
- **FP-3 Compensation**: irreversible operations (audit events, issued short codes, recorded approvals/decisions, recorded analytics events, released/tagged/published artifacts — FR-027) are never rolled back; attempted rollback is rejected and audited, and the recorded compensating action applies (FR-049). Classification of remaining operations: irreversible unless the design shows a rollback — classification table in §6 (D-002, resolved).
- **FP-4 Safe-stop**: when continuation is unsafe (retry exhaustion on a mandatory path, double approval timeout, governed termination), the run enters SAFE_STOPPED with deterministic preserved state; resumption requires recorded conditions (cause addressed, plus human approval where the stop involved a governed decision) — FR-028. Retry eligibility for failure types beyond approval timeouts/rejections is resolved as transient-only (D-001, classification in §6).

**Node definitions.**

| Node | Purpose | Inputs → Outputs | Entry / Exit criteria | Actor | Notable behavior |
|---|---|---|---|---|---|
| N01 ingestion | Normalize the raw requirement into a stored artifact with provenance | Raw requirement text → RequirementArtifact | Non-empty input / artifact stored, run created | Agent | Malformed input → defined rejection, audited |
| N02 quality-check | Evaluate completeness, consistency, testability, policy fit | RequirementArtifact → quality-check report | Artifact exists / report recorded with no-clarification rationale (SCN-A) or ambiguity findings | Agent | Well-defined → proceed without artificial gate (FR-034); material ambiguity → N03 (FR-035) |
| N03 clarification-gate | Suspend the affected path, request human clarification, record decision and downstream impact | Ambiguity report → clarification record | Ambiguity detected / human decision recorded | Human | Affected path only; independent paths continue to sync point; resumption at correct state (FR-035) |
| N04 impact-analysis | Identify impacted components, interfaces, data flows, tests, docs, regression risks, rollout/rollback before any change | Change request + current system → impact-analysis artifact | Brownfield or replanning trigger / impact artifact recorded and reviewed | Agent → human | Precedes any code change (US-003, SCN-B) |
| N05 decomposition | Break the requirement into tasks with explicit dependencies | Approved artifacts → task graph rows | Design inputs present / tasks + edges persisted | Agent | |
| N06 design | Produce API/schema design and ADR proposals | Tasks → design artifacts, ADR proposals | Entry criteria of design tasks / artifacts versioned | Agent (human/AI-assisted ingestion) | |
| N07 adr-approval-gate | Human approval of architecture decisions and implementation start | ADRs, design artifacts → approval record | ADRs proposed / approval recorded | Human | Implementation start requires approval of the current approved design/task revision (FR-024) |
| N08 implementation | Execute tasks: fan-out parallel executors, synchronization point | Approved tasks → code, tests | N07 approved / **all task nodes SUCCEEDED** (failed or stopped tasks propagate path failure — no silent pass-through) | Agent | Parallel paths prove non-linear execution (FR-043); per-task FP-1/FP-2 |
| N09 testing | Execute the test suites, record outcomes | Implementation → test-outcome rows | N08 exit / suites executed, outcomes recorded | Agent | Red-green discipline evidence retained |
| N10 documentation | Update docs to current state | Design + implementation → documentation artifacts | N08 exit (runs parallel with N09) / docs current | Agent | |
| N11 validation-evidence | Assemble validation evidence against acceptance criteria and PVTs | Test outcomes → evidence records | N09 and N10 synchronized / evidence complete or gaps identified | Agent | Gaps → bounded rework or NOT READY path |
| N12 policy-evaluation | Evaluate the versioned policy set; outcomes exactly PASS / FAIL / EXCEPTION-REQUESTED / NOT-APPLICABLE | Run artifacts → PolicyEvaluation rows | Evidence stage done / all applicable checks evaluated | Agent | A failed mandatory check blocks release **authorization**, not the determination — see N13 |
| N13 release-readiness-gate | Produce the system's recommendation and obtain the human release decision | Policy outcomes, evidence, limitations → recommendation + decision record | All policy checks evaluated / recommendation and decision recorded | Human | On failed mandatory policy: the NOT READY determination with blocking reasons is persisted at N12 with a blocked-transition audit; the N13 release-authorization path is **not** taken (N14 runs reporting-only, §9). Where a run reaches authorization, the human decision is a separate approval/rejection record (FR-037, CL-5) |
| N14 engineering-summary | Produce terminal summary: rationale, artifacts, risks/trade-offs, validation, assumptions, limitations | All prior artifacts → EngineeringSummary | N13 decided **or** N12-persisted NOT READY (reporting-only route) / summary stored | Agent | Governed terminal-reporting node: after a failed mandatory policy check it may run to produce the **NOT READY summary only** (FR-038) — see note below the table. FR-044 completion gate independently blocks any "complete" declaration when evidence, approvals, traceability, or documentation are incomplete |

**N12/N13/N14 and FR-033 / Constitution VI.** A failed mandatory policy check blocks downstream workflow progression (FR-033) — specifically, it blocks release **authorization** and any READY or READY WITH ACCEPTED LIMITATIONS outcome. N14 is a governed terminal-**reporting** node: it is the one node permitted to execute after a failed mandatory check, and only to produce the NOT READY summary. This preserves Constitution VI (a failed mandatory policy check blocks release readiness) while still terminating the run deterministically with reportable evidence: the summary names the failing check, the release-readiness decision is NOT READY, no release approval is issued, and the FR-044 completion gate prevents any completion declaration.

**Human approval checkpoints** (FR-024, minimum set): start of implementation (N07); changes to approved requirements/architecture/schemas/workflow states; policy exceptions; acceptance of dynamic replanning; release authorization (N13). Every attempt to progress past a gate without approval is blocked and audited (SC-004).

**Dynamic replanning** (FR-029): an upstream artifact change event re-validates downstream artifacts, evidence, and approvals rather than assuming validity; invalidation relationships are recorded; replan acceptance requires human approval; replanning history is persisted as rows. Concrete triggers and state-recovery details are defined in §9 (D-006, resolved).

**Safe-stop and resumption** (FR-028): as defined in FP-4. Demonstration: kill the JVM mid-run, restart, reconcile operation outcomes, resume with no duplicate side effects (PVT-006), including the after-effect/before-completion-record case.

### 4. State and Decision Lineage

All entities below are control-plane-owned rows in the shared H2 store; each carries the run correlation identifier (NFR-005).

- **WorkflowRun** — run id, requirement reference, evaluated policy version (FR-032), status, optimistic-lock version, **current audit-chain head hash and event count** (independently retained expected values for truncation detection).
- **StageNode + edges** — decomposition, dependencies, per-node status and criteria state, branch-selection records and NOT_APPLICABLE reasons (FR-020, FR-021, FR-036).
- **RequirementArtifact** — normalized requirement text with provenance tags (Confirmed/Derived/Proposed), quality-check reports (FR-034), **and recorded assumptions**.
- **Approval** — requester key identity, approver key identity and role (must differ, FR-046), decision, scope, escalation state, second-window timing, authorized artifact(s) (FR-024, CL-2). Rejections and their reasons are retained, never deleted.
- **AuditEvent** — append-only; actor type, action, timestamp, affected artifact/state, result, reason; per-run sequence number; hash over the canonical serialization of (run id, seq, predecessor hash, event fields); unique constraint on (run id, seq) and the chain-head update commit in the same transaction — parallel nodes cannot create competing chain heads. No update/delete endpoints exist (FR-048, CL-3). A separate **platform-level audit stream** (its own chain) records authentication failures and rate-limit events that occur outside any workflow run.
- **Verification (FR-048)** — the verifier walks the run's chain: recomputes each hash from the canonical input, checks predecessor links and sequence continuity, and compares the final head and count against the independently retained values on WorkflowRun (detecting truncation of trailing events). The chain is evaluated at N12 and **re-verified at N14** after the N13/N14 terminal appends. Result: pass, or failure with the affected position and check. Wording per A-011: this **detects** tampering (alteration, removal, reordering, truncation) for the stated checks; it does not prevent tampering by someone with direct storage access, who could also alter the retained reference — disclosed limitation.
- **PolicyEvaluation** — check id, outcome (PASS/FAIL/EXCEPTION-REQUESTED/NOT-APPLICABLE), evidence reference; exception records carry policy, reason, scope, approving authority, compensating control, timestamp, expiry/review condition (FR-032, FR-033).
- **ReplanningEvent / decision-lineage rows** — decision, rationale, **rejected decisions and their reasons**, affected artifact versions and their **content hashes**, invalidation relationships (which downstream artifacts/evidence/approvals were re-validated or invalidated) (FR-023, FR-029).
- **TestOutcome / RiskOutcome rows** — linked to requirement identifiers (SC-002).
- **EngineeringSummary** — terminal work product (FR-038).
- **Terminal status** — deterministic and persisted on the run; safe-stop cause and preserved state recorded (US-005 scenario 5).

**Reconstruction guarantee (US-005):** the run history endpoint plus SQL over these tables yields the full decision lineage — states, decisions, approvals, retries, failures, replanning, terminal outcome — without access to developers' memories; the chain-verification endpoint reports integrity against the stated checks and the retained reference.

### 5. Human-in-the-Loop Controls

**Mandatory approval gates (FR-024)** — each gate record has states PENDING → APPROVED | REJECTED | ESCALATED → (second timeout) affected path SAFE_STOPPED; default timeout 5 min (PVT-005); escalation re-issues once with an equal second window; timeouts and rejections are non-retryable; silence is never approval.

| Gate | Trigger | Authority |
|---|---|---|
| G1 unresolved ambiguity | N03 clarification-gate (SCN-C, or mid-run SCN-A) | Human candidate |
| G2 architecture approval | N07 ADR/implementation-start gate | Human candidate |
| G3 security-sensitive action | Changes to security controls, keys, rate limits | Human candidate |
| G4 destructive/irreversible action | Any destructive change (none exists in the prototype — attempts are rejected and audited) | Human candidate |
| G5 constitutional/policy exception | Policy exception request (FR-033) | Human candidate |
| G6 material risk acceptance | Open-limitation acceptance for READY WITH ACCEPTED LIMITATIONS | Human candidate |
| G7 release readiness | N13 release authorization | Human candidate |
| G8 final submission | Repository tag/push as the assessment submission | Human candidate |

**Rules.** Requester and approver key identities must differ at every gate, including replan acceptance (D-006); self-approval and agent approval return 403, leave the gate pending, and are audited (FR-046). Agents never approve. Blocked progression attempts are blocked and audited (SC-004). Rejection leads to bounded rework or governed termination, never automatic retry. Approvals record approver **key ID only** (D-004). **Exception governance:** no exception may be granted against the non-waivable principles (II, III, VIII, X, XI); an approval of an open limitation (G6) is valid only when every limitation is individually approved, no mandatory policy has failed, and no non-waivable principle is among them — approval can never override a mandatory FAIL (FR-037).

### 6. Reliability

**Failure classification (D-001; unknown failures default to permanent):**

| Failure | Class | Handling |
|---|---|---|
| Storage connection timeout, worker saturation | Transient | FP-1 retry |
| Operation timeout, optimistic-lock conflict | Transient, **retry only after reconciling uncertain effects** (operation-identity completion-record check) | FP-1 retry after reconcile |
| Validation/logic errors, policy FAIL | Permanent | Node failure behavior (below) |
| Approval timeout, approval rejection | Permanent, non-retryable | Escalation path / bounded rework or governed termination (FR-025) |
| Unknown failure | Permanent (default) | FP-4 safe-stop — never silently retried |

**Numeric bounds (proposals, recorded in configuration, confirmed at Phase 1):** exponential backoff 1 s → 2 s → 4 s with ±20% jitter; per-operation timeout 10 s; shared automated-path deadline 60 s — retry exhaustion always yields a deterministic terminal outcome within 60 s (PVT-004).

**Stage-group failure behaviors (per-stage fallbacks; refined per node in Phase 1 data-model):**

| Node group | Transient | Permanent / fallback | Failure behavior |
|---|---|---|---|
| N03, N07 human gates | **No retries** — timeouts/rejections non-retryable (FR-025); escalation per PVT-005 | — | Bounded rework or governed termination |
| N01, N02, N05, N06 deterministic artifact transforms | FP-1 retry | Regenerate deterministically from inputs | Permanent failure → rework task or governed termination |
| N08 implementation tasks | FP-1 retry **after reconcile** | FP-2 alternative **only if pre-approved with the task**; otherwise **safe-stop** (explicitly selected — no invented fallback machinery) | Compensation or safe-stop (FP-3/FP-4) |
| N09 testing | Re-run suite (idempotent) | Repeated failure → permanent | Evidence gap → NOT READY path |
| N10 documentation | Regenerate (idempotent) | — | Path failure |
| N11–N12 evidence/policy | FP-1 retry | Policy FAIL is deterministic — no retry | Blocks release authorization (§9) |

**Idempotency and duplicate-execution protection.** Request-identity unique constraint (FR-007); durable operation identities + completion records; on resumption, operations without a completion record are reconciled or the run safe-stops — never blindly re-executed (§3).

**Rollback vs compensation (D-002; FR-027):**

| Operation | Class | Recovery |
|---|---|---|
| Audit-chain appends, issued short codes, recorded approvals/decisions, recorded analytics events, released/tagged/published artifacts (FR-027 list) | Irreversible | Compensation recorded and applied (FR-049); attempted rollback rejected + audited |
| Candidate rows inserted inside an **uncommitted transaction** | **Reversible** | Rollback: inject failure before commit, transaction rolls back, verify no rows remain — the demonstrable rollback case (FR-027) |
| Unclassified operations | Irreversible (default) | Compensation |

**Safe-stop and resumption.** FP-4 causes: retry exhaustion on a mandatory path, double approval timeout, governed termination, unknown failure on a mandatory path, or no viable fallback (FR-026). Resumption requires recorded conditions (cause addressed) plus human approval where the stop involved a governed decision (FR-028). PVT-006 demonstration: kill-and-restart, including interruption after an effect but before its completion record — zero duplicate side effects.

**Partial failure and dependency unavailability.** A failed or stopped path is isolated; independent parallel paths proceed to the synchronization point, which waits for resolution of the stopped path (FR-024 edge case). H2 unavailability yields defined errors with no partial or incorrect redirects (FR-009); DNS lookup is bounded (timeout, cached) and unresolved results are rejected; health checks expose dependency reachability (FR-010).

### 7. Observability

**Records.** Structured logs (no secrets or raw keys — key IDs only), metrics, and tracing implemented without new dependencies: the correlation ID in the logging context (MDC) plus span-like records in the audit tables (stage start/end, duration, outcome), audit events with the FR-030 field set, and queryable state-transition, decision, approval, retry, compensation, and replanning histories (US-005).

**Demonstration metrics (FR-031; labeled demonstration-grade, distinct from production measurements — C-004):** workflow success rate, failure rate, retry frequency, rollback/compensation frequency, end-to-end latency, unrecovered failure count, and MTTR, exposed at `GET /api/v1/metrics/demo` (control-plane endpoint; requires the **reviewer** role key, FR-045; per-key limit A-008c).

**MTTR capture (per failure event):** failure-detected timestamp, recovery-start timestamp (retained separately to show detection-to-start lag), recovery-complete timestamp, individual recovery duration = recovery-complete − failure-detected (governance wait occurring inside an episode is included in the duration **and** reported separately as governance-wait time), recovery mechanism used, recovered-vs-unrecovered status. **Aggregation:** number of recovered failure events, total recovery duration, MTTR = total recovery duration ÷ recovered failure events — unrecovered failures are excluded from the denominator and reported separately (Constitution Evidence and Integrity Constraints). With zero recovered failures, MTTR is reported as N/A, not zero. **Measurement population:** demonstration runs with fault injection, not production load (C-004 limitation).

### 8. Security

**Input validation.** Size limit, whitespace stripping, canonical normalization (FR-002, FR-013); scheme allowlist HTTP/HTTPS only (A-007).

**Malicious redirect and internal addresses (D-003).** The service **never fetches target URLs** — resolution returns the recorded target, so there is no SSRF fetch surface; creation-time address validation is abuse reduction. Rejected at creation: loopback (127/8, ::1), RFC 1918, fd00::/8, link-local (169.254/16, fe80::/10), 0.0.0.0, cloud-metadata addresses (169.254.169.254), IPv4-mapped IPv6, decimal/octal IP forms, `localhost` names, and URLs containing userinfo. Checks run after bounded DNS resolution against all resolved IPs; unresolved results are rejected. **Resolver mode is configurable:** `live` (real DNS) or `fixed-local` (fixed local resolution table, no network access — demonstration default; offline-safe, deterministic demos); tests inject the stub resolver. Also rejected (additions approved by the human candidate 2026-10-18): IPv6 unspecified `::`, full unique-local fc00::/7, and localhost subdomains (`*.localhost`). **Recorded limitation:** DNS rebinding after creation is not re-checked (target fixed at creation).

**Abuse containment.** Rate limits A-008a-d via the Bucket4j servlet filter: 429 + retry hint, no short code allocated, audited, configurable low limits for tests (FR-047). Bucket-map eviction removes only entries whose removal cannot restore restricted capacity (long-idle identities); when capacity is exhausted, new identities are rejected rather than discarding active limits — enforced by a capacity-pressure test. Failed-auth temporary block: 5 min, configurable (D-004).

**Authentication assumptions and secrets (D-004, A-009).** Role-scoped keys are a local-prototype mechanism. Keys are stored as SHA-256 hashes only, compared in constant time; audit events record key ID only; raw demo keys come from a local gitignored source, labeled demo-only; raw keys never appear in logs or commits (NFR-001).

**Dependency risk.** Pinned versions; dependency/license inventory; dependency and secret scanning at release readiness (Constitution VI, NFR-001). **Build-time policy tools** (build-scope dependencies, themselves listed in the DEP-01 inventory): gitleaks (secret scan, offline — SEC-03); OWASP DependencyCheck Maven plugin (vulnerability scan — DEP-02; requires NVD network access, so **offline demonstrations degrade DEP-02 to attestation** with last-cached data and a recorded degradation note); license-maven-plugin (license inventory check, offline — LIC-01).

**Audit integrity.** Hash-chained append-only events with verification (§4); detects alteration, removal, reordering, truncation; does not prevent tampering via direct storage access (A-011).

**Least privilege and secure defaults.** No update/delete operations on audit or workflow records; control plane deny-by-default; validation on by default; roles scoped to their plane.

**Threat model (summary).** Public abuse → rate limits; SSRF-adjacent targeting → no-fetch design + address rejection; audit tampering → hash chain (A-011); self-approval → 403 + audit; secret leakage → hashed storage, key IDs only, gitignored demo keys; quota evasion via eviction → eviction rules + capacity-pressure test.

### 9. Compliance and Change Control

**Policy domains (versioned set, `POL-2026-10-18-v1`):** security, compliance, privacy, audit retention, approved dependencies, software licensing, change control (Constitution VI). The evaluated policy version is recorded on every run (FR-032).

**Evaluation model.** Checks run at N12 (post-evidence sweep) and as embedded exit-gate guards on every stage — no stage bypasses them (FR-039). Inputs: run artifacts, scan results, evidence references. Output per check: exactly one of PASS / FAIL / EXCEPTION-REQUESTED / NOT-APPLICABLE, stored as PolicyEvaluation rows with evidence references. All seven domains are mandatory for release; advisory checks are flagged as such.

**Change-request workflow.** Change request → N04 impact analysis (components, interfaces, data flows, tests, docs, regression risks, rollout/rollback) → C-005 change approval → versioned artifact updates → downstream revalidation (D-006 triggers).

**Policy exception workflow.** Written proposal: applicable policy, reason, scope, compensating control, expiry/review condition → human approval with approver identity different from the requester, **approving authority and approval timestamp recorded** → stored in PolicyEvaluation and Approval. Exceptions against non-waivable principles (II, III, VIII, X, XI) are prohibited. An unapproved or expired exception yields NOT READY (FR-037 scenario 3).

**Upstream-change impact analysis (D-006).** Trigger set: input artifact version change, approval invalidation, policy version change. On trigger, downstream artifacts, evidence, and approvals are re-validated (invalidation relationships recorded); an optimistic-lock conflict on resume forces revalidation before proceeding. Replan acceptance requires human approval by an approver identity different from the requester.

**Versioning and revalidation.** Changed requirements, architecture, contracts, schemas, tests, and documentation receive new versions; downstream artifacts are re-validated and re-approved; the traceability matrix is updated (§13).

**Release-blocking path (explicit, FR-033).** Normal path: `N12 all mandatory PASS → N13 recommendation + human authorization → N14 summary`. Policy-failure path: `N12 mandatory FAIL → persist NOT READY determination (with blocked-transition audit) → N14 reporting-only`. The failing check blocks all other downstream execution and the N13 release-authorization path is **not** taken; no release authorization or completion declaration is possible (Constitution VI, FR-037).

**Planned demonstration (not yet executed — no execution evidence exists at planning time).** A fault-injection test forces the mandatory check **POL-DEP-01 (approved-dependencies: every runtime dependency pinned and declared in the dependency/license inventory)** to FAIL, simulated by an undeclared dependency in the build. Expected evidence: FAIL PolicyEvaluation row; blocked-transition audit event; NOT READY determination; N14 NOT READY summary; absence of any release-approval record; FR-044 completion gate blocking completion. This test, once executed and captured, is the demonstration that failed mandatory policy checks prevent release readiness.

### 10. Testing

Test types (guide-required) mapped to what they verify; the seven NFR-009 categories, tools, and packages are in Technical Context. Red-green-refactor applies to domain and orchestration behavior (C-003): failing test first, shown failing for the expected reason, minimum code to pass. Evidence = executed output (FR-044, Constitution X — never fabricated).

**Execution wiring:** Surefire runs unit/orchestration/reliability/security/contract tests (`*Test`); Failsafe runs integration and e2e (`*IT`) — `mvn verify` executes every category; ArchUnit rules run in the unit phase and fail the build on layer violations.

| Suite | Verifies | Requirement / PVT |
|---|---|---|
| Domain unit | Create/resolve/expire/idempotency/normalization logic; never-reassign policy | FR-001 to FR-016 |
| API contract | Request/response schemas vs `contracts/openapi.yaml`; error responses (400/404/409/410/429 — 409 approved 2026-10-18 as a spec delta; 302/410/base-62 remain proposals for contract review); redirect semantics | FR-002, FR-005, FR-006, FR-014 |
| Persistence | Constraint-enforced idempotency races; hash-chain integrity; optimistic locking; runs on H2 and (later) PostgreSQL | FR-007, FR-016, FR-048, PVT-003 |
| Integration | Context wiring, repositories, health, analytics capture (stub DNS resolver injected) | FR-010, FR-012 |
| Orchestration transition | State machines, prohibited transitions, gates, NOT_APPLICABLE branch semantics | FR-020 to FR-024, SC-004 |
| Approval / rejection | Approve/reject/timeout/escalation; self-approval 403; silence never approval | FR-024, FR-046, PVT-005 |
| Retry / timeout | Bounded retries, backoff, 60 s terminal bound, reconcile-before-retry | FR-025, PVT-004 |
| Fallback | FP-2 alternatives per stage group; safe-stop when none pre-approved | FR-026 |
| Rollback / compensation | Uncommitted-transaction rollback case; irreversible rejection + compensation | FR-027, FR-049 |
| Safe-stop / resumption | Kill-and-restart; after-effect/before-completion-record case; zero duplicate effects | FR-028, PVT-006 |
| Replanning | Trigger set, invalidation, approver-differs rule | FR-029, D-006 |
| Concurrency | 100 concurrent creations with stub DNS and a controlled (fresh, test-configured) rate-limit initial state; duplicate-identity races | FR-008, PVT-003 |
| Performance | PVT-001 (≥ 99% resolution success) and PVT-002 (≥ 95% < 200 ms): recorded workload (request count, population of valid active links), environment (local, single JVM), and measured results; failures reported as deviations through the FR-033 process | PVT-001, PVT-002 |
| Security | Address rejection (incl. approved additions), 429 behavior, 401/403, key hashing, capacity pressure | FR-045 to FR-047, A-008, D-003, D-004 |
| End-to-end | SCN-A/B/C full runs | FR-040 to FR-043 |
| Release-readiness | Policy outcomes, NOT READY path, forced mandatory-FAIL demonstration (POL-DEP-01), completion gate | FR-032, FR-033, FR-037, FR-044 |

**PVT-008:** workflow success rate and MTTR (N/A when no recovered failures) are reported for **every** demonstration run, alongside the other demonstration metrics (§7).

### 11. Scenario Designs

Scenario inputs below are **proposed demonstration fixtures** — demonstration requirements for the orchestration system, not newly approved product requirements.

**SCN-A — Greenfield (well-defined).**
- Initial input (fixture): "Analytics summary endpoint returning redirect counts by outcome for a link" (complete, consistent, testable, within policy).
- Interpretation: requirement-quality checks pass; no-clarification rationale recorded (FR-034).
- Decomposition: tasks with dependencies (API contract, domain logic, persistence, tests, docs).
- Orchestration path: N01 → N02 → N05 → N06 → G2 (N07) → N08 fan-out → {N09 testing ∥ N10 documentation} → sync → N11 → N12 → G7 (N13) → N14.
- Approvals: G2 implementation start; G7 release readiness.
- Failure paths: transient storage failure → FP-1; validation gap → bounded rework; mid-run material ambiguity variant → only the affected path suspends (N03), resumption at correct state.
- Validation: acceptance scenarios executed; PVT checks; contract tests green.
- Evidence: quality-check report with no-clarification rationale, task graph, test outcomes, policy outcomes, approval records, demonstration metrics.
- Terminal outcome: run status COMPLETED with a READY recommendation **conditional on evidence and approval** — not guaranteed by the scenario.

**SCN-A variant — non-linear execution in one run (SC-003, SC-006).** The same run is designated to demonstrate, in one execution: parallel paths with synchronization (N08 fan-out; N09 ∥ N10); mid-run suspension and resumption (kill-and-restart at a defined point, resumed from durable state with zero duplicate effects, PVT-006); and an **approved dynamic replanning event** (an upstream design-artifact version change triggers downstream re-validation; replan accepted by an approver identity different from the requester). A reviewer then reconstructs this run using only repository artifacts and persisted records (US-005, SC-006).

**SCN-B — Brownfield (change).**
- Initial input (fixture): change request against the existing shortener: "Change the default link TTL from 7 days to 24 hours." Changing the approved default (A-006) is a governed change requiring C-005 approval; existing links keep their already-effective expirations (no retroactive change).
- Interpretation: brownfield change; impact identification precedes any code change (US-003).
- Decomposition: tasks derived from the impact analysis (config, domain default, tests, docs).
- Orchestration path: N01 → N02 → N04 (impact analysis) → N05 (decomposition) → N06 (design) → G2 (N07, change + implementation start) → N08 → {N09 ∥ N10} → N11 → N12 → G7 → N14.
- Approvals: G2 (change approval + implementation start); G7.
- Failure paths: regression risk materializes → bounded rework; policy violation variant → blocked progression with the recorded exception process.
- Validation: regression suite green; impact analysis verified against the actual diff.
- Evidence: impact-analysis artifact (components, interfaces, data flows, tests, docs, regression risks, rollout/rollback), traceable execution evidence.
- Terminal outcome: run status COMPLETED with a READY or READY WITH ACCEPTED LIMITATIONS recommendation, conditional on evidence and approval.

**SCN-C — Ambiguous requirement.**
- Initial input (fixture): "Add support for links."
- Interpretation: quality check detects material ambiguity; unsafe implementation prevented — no implementation work product from ambiguous input (FR-035).
- Decomposition: none until clarified (this is part of the demonstration).
- Orchestration path: N01 → N02 (ambiguity findings) → N03 clarification-gate: affected path suspends, human clarification requested; recorded decision ("links" = short links; "support" = create + resolve) → artifacts updated → resumption at correct state (not from the beginning) → N05 → … → N14.
- Approvals: G1 clarification; then G2, G7.
- Failure paths: clarification timeout → escalation (one re-issue) → safe-stop of the affected path; independent paths continue to the sync point.
- Validation: post-clarification run proceeds normally; resumption state verified.
- Evidence: ambiguity report, clarification record with human decision and downstream impact, resumption record, safe-stop/escalation evidence in the timeout variant.
- Terminal outcome: run status COMPLETED after clarification (timeout variant: SAFE_STOPPED, resumable).

### 12. Technology Decisions

Full records (context, drivers, options, consequences, risks, reversibility, validation) in ADR-001 to ADR-005 at the ADR gate — the gate may identify **additional** material decisions beyond these five. Summary (ADR-level vs implementation):

| # | Decision | Level | Selected | Rejected | Rationale | Risk | Reversibility | Validation |
|---|---|---|---|---|---|---|---|---|
| 1 | Language/runtime | ADR-001 | Java 21 LTS | TypeScript/Node 22; Python 3.12 | Fluency under timebox; typed state/audit invariants | Boilerplate | Low (rewrite) | Compile + suites |
| 2 | Web framework | ADR-002 | Spring Boot 3.x | Quarkus 3.x; Javalin/JDK | Actuator (FR-010), security filter chain, fluency | Auto-config magic vs domain purity | Medium (thin adapters) | ArchUnit + contract tests |
| 3 | Persistence | ADR-003 | H2 file mode | SQLite; PostgreSQL; in-memory | Zero-install durability + constraint-enforced concurrency | Not a production DB (disclosed) | High (repo interfaces; PG test suite) | Persistence tests (H2 + PG) |
| 4 | Test stack | Implementation | JUnit 5 + Spring Boot Test + AssertJ + Mockito + REST Assured + ArchUnit | Spock; TestNG | Spring-native; all 7 categories; container-free domain tests | Multi-library stack | Moderate (test assets) | Executed suites |
| 5 | Local run model | ADR-004 | Single fat jar (Maven, single module) | Two processes; Docker Compose | Simplest setup; kill-restart = PVT-006 demo | Single process failure domain (disclosed, acceptable) | Medium (module boundaries) | Kill-restart test |
| 6 | Workflow state | ADR-005 | Relational tables in H2 | Event-sourced journal; JSON files | Transactional state-audit coupling; queryability | Lineage spread across tables (mitigated: history view) | Medium | Orchestration tests + chain verifier |
| 7 | Rate limiting | Implementation | Bucket4j (approved) | Hand-rolled; Resilience4j | Tested token-bucket; Apache-2.0 | In-memory reset on restart (disclosed) | High (filter) | Rate-limit + capacity tests |
| 8 | OpenAPI validation | Implementation | swagger-request-validator-restassured (pending Phase 0 approval) | Hand-written schema assertions | Executable contract validation | Extra dependency (needs approval) | High | Contract tests |

### 13. Traceability

Chain: **Requirement → Scenario → Design → ADR → Task → Code → Test → Validation → Documentation → Evidence.**

- Requirement IDs (FR/NFR/PVT/A/C) appear on task rows, test methods (naming convention + tag), and artifact provenance.
- Scenario designs (§11) reference graph nodes (§3); design decisions reference ADR-NNN; tasks (generated at the tasks stage) reference ADRs and requirement IDs.
- Commit messages carry task IDs (small, coherent commits — Constitution X); test outcome rows link to requirement IDs; evidence records link to test outcomes.
- **Mapping manifest:** a versioned manifest (checked into the repository, updated per task group) records, per requirement ID: the ADR file reference, the SHA of the referenced commit (never the manifest's own commit SHA), test identifiers, documentation version, and evidence file reference with artifact hashes. The traceability matrix is generated by joining this manifest with the persisted runtime tables (WorkflowRun/StageNode/TestOutcome/PolicyEvaluation/Approval) — not from either alone.
- **Bidirectional verification:** a check reports requirements with no evidence link and evidence with no requirement link; both directions must be clean. FR-044 completion gate checks traceability completeness before any completion declaration; US-005 reconstruction relies on the same records.

### 14. Delivery Sequence

**Vertical slices** (guide order) with day-level milestones. Planning (this plan, ADRs, checklist, tasks, analysis) is part of the 2–3 day timebox — implementation starts only after the plan/ADR, checklist, tasks, analysis, and pre-implementation gates pass.

| Slice | Content | Milestone |
|---|---|---|
| 1. Engineering baseline | Maven project, package layout, ArchUnit rules, H2 schema baseline, `mvn verify` wiring | Day 1 AM |
| 2. Walking skeleton | Both planes boot; health endpoint; one create + redirect e2e; audit chain writes | Day 1 midday |
| 3. Core URL behavior | Validation, idempotency, expiration, analytics capture, concurrency (PVT-001..003) | Day 1 PM |
| 4. Orchestration state model | Graph scheduler, node states, transitions, audit-coupled commits (PVT-007) | Day 1 EOD |
| 5. Approval governance | Gates G1–G8, escalation, safe-stop, self-approval denial (PVT-005) | Day 2 AM |
| 6. Reliability controls | Retries, fallback, rollback/compensation, resumption (PVT-006), replanning | Day 2 midday |
| 7. Observability | Metrics, MTTR capture, history/verify endpoints | Day 2 PM |
| 8. Three scenarios | SCN-A/B/C executed runs + policy evaluation + release readiness (forced-FAIL demo) | Day 2 EOD |
| 9. Release readiness | Convergence evidence, traceability matrix, final engineering summary | Day 3 |

**Critical path:** baseline → walking skeleton → core URL → orchestration state model → approval governance → scenarios + policy → release-readiness evidence. Slices 1–8 are sequential on this path; documentation runs as slice-parallel work (N10).

**Minimal implementations (disclosed):** parallel executors use a simple bounded thread pool (no actor framework); the policy evaluator is a rule-based checker over the named check inventory (data-model.md); stage executors are the two labeled classes (§3); analytics reporting is a single summary query. **Cut order:** the tasks stage must define an explicit drop order for optional work under timebox pressure — first the backlog items (Dockerfile, metadata endpoint, dashboards, pagination, key rotation), then optional presentation work and demonstration-metric depth (PVT-008 reporting remains mandatory), then simplification of demonstration fixtures — while never cutting mandatory validation, reviewer evidence, or the three scenarios (C-006).

**Scope-control checkpoints:** end of Day 1 (core URL + state model green; if not, cut optional presentation work and extra queries — required analytics reporting (FR-012) is never cut); mid-Day 2 (scenario runs executing, else simplify demonstration fixtures); end of Day 2 (evidence captured incrementally through the day — Day 3 is reserved for validation, reviewer reconstruction, and rework, not first-time assembly). Optional work never displaces mandatory validation or reviewer evidence (C-006).

**Stop conditions:** a discovered constitution violation; a mandatory PVT failing with no fix inside the remaining buffer; an approval gate blocking scenario completion beyond its escalation window (escalate to the human candidate); timebox exhaustion with mandatory work incomplete — the honest outcome is a NOT READY determination with disclosed gaps, never fabricated evidence (C-004).

**Minimum defensible release-readiness outcome:** READY WITH ACCEPTED LIMITATIONS, per FR-037/FR-044 literally — no mandatory policy FAIL; no unapproved or expired exception; all required completion evidence (executed acceptance scenarios including negatives, SCN-A/B/C traceable evidence, SC-003 single-run demonstration, PVT-001..008 reported for every demonstration run with deviations handled through the FR-033 exception process, complete bidirectional traceability, current documentation); every eligible open limitation individually approved with no non-waivable principle among them. NOT READY is the honest fallback when mandatory work remains incomplete.

**Backlog (deferred optional work):** Dockerfile (E-002 adjacent); per-link metadata endpoint; INCOMPLETE release outcome (A-012); distributed/shared-backend rate limiting; key rotation API (beyond D-004 scope); multi-module Maven split; PostgreSQL as default store; metrics dashboards; pagination on history endpoints.

## Complexity Tracking

*Fill only if Constitution Check violations require justification. None identified at planning time; if implementation reveals one, it must be recorded here with justification or the plan is revised (Constitution Check, above).*
