# Feature Specification: Agentic Software Engineering System: URL Shortener
**Feature Branch**: `001-agentic-url-shortener`
**Created**: 2026-10-18
**Status**: Approved — Human Gate 2 passed 2026-10-18 (human candidate decision; conformance edits and proposal approvals applied same date; see Human Gate 2 Decision Record). Clarify session 2026-10-18 (CL-1 to CL-5) integrated; Human Gate 3 passed 2026-10-18 (CL-1 to CL-5 reviewed and approved by the human candidate)
**Input**: User description: "Build a production-grade prototype titled 'Agentic Software Engineering System: URL Shortener' — a governed agentic execution system that transforms software requirements into reviewable engineering outcomes across the complete SDLC, demonstrated through a URL shortener service (creation, short-code generation, redirect resolution, analytics, expiration, validation, reliability, health) coordinated by a non-linear, stateful orchestration system with human governance, retries, fallback, rollback/compensation, safe-stop, resumption, dynamic replanning, audit-grade evidence, and reliability measurements."

## Requirement Provenance

Every requirement in this specification carries a stable identifier and a provenance tag:

- **[Confirmed]** — directly supported by the official assignment, the approved execution guide (Prompt 2), the approved Constitution v1.0.0, or recorded human clarifications and decisions.
- **[Derived]** — necessarily implied by a confirmed requirement; recorded so it is traceable and reviewable.
- **[Proposed]** — a default or validation target proposed in the absence of an assignment-specified value; requires human approval before it is treated as binding (all proposals in this specification were approved 2026-10-18 — see Validation Targets and the Human Gate 2 Decision Record).

Scenario identifiers: US-nnn (user stories), SCN-A/B/C (demonstration scenarios), FR-nnn (functional), NFR-nnn (non-functional), SC-nnn (success criteria), A-nnn (assumptions), PVT-nnn (validation targets).

## Amendment Log

- 2026-10-19 (C-005, spec deltas recorded in plan section 2; re-acceptance at T124): the request identity is carried in the Idempotency-Key header; same key and same normalized payload returns the existing link with 200 (a new link returns 201), including after the link has expired; same key and different payload returns 409 and is audited; a missing or malformed key returns 400 and allocates no short code.
- 2026-10-19: 302 (active), 410 (expired) and the base-62 alphabet are adopted at the contract level.
- 2026-10-19: MTTR excludes governance wait (reported separately, plus a labelled secondary figure).
- 2026-10-19: replan control (staged revision, HELD nodes, cap 2), rework cap 2, sync-wait bound of 2 approval windows, boot recovery to SAFE_STOPPED, DNS lookup timeout 3 s, and resume of governed stops by an APPROVER key are plan-level details of FR-024, FR-028 and FR-029.

## Clarifications

### Session 2026-10-18

- CL-1 — Q: How are the control-plane API and approvals authenticated, and how is abuse contained? → A: Redirects are public; link creation is open (A-002) with per-IP rate limiting (A-008a-d); orchestration and approval endpoints require role-scoped keys (engineer, approver, release owner, reviewer), a local-prototype mechanism and not production identity. Self-approval is not allowed: the approver key identity must differ from the requester's, and a self-approval attempt returns 403 and is audited. Agent actors can never approve their own decisions.
- CL-2 — Q: When an approval request goes unanswered past its timeout, what happens? → A: Option B: the approval becomes escalated and is re-issued once with a second timeout window of the same length, with an audit event; with no response the path enters safe-stop. Silence is never approval. Approval timeouts and rejections are non-retryable. Limitation: escalation re-notifies the same single human authority (FR-024/E-003).
- CL-3 — Q: How long are audit records kept, and must they be tamper-evident? → A: Option B: audit and workflow records are kept through the assessment review period (A-005); the application exposes no update or delete operation; each run's events are hash-chained with a verification check that detects altered, removed, or reordered events. No purge in the prototype; any purge is a destructive action requiring explicit human approval. Limitation: hash-chaining detects tampering but does not prevent it for someone with direct storage access. Audit retention remains separate from analytics retention (A-003).
- CL-4 — Q: Which changes must be undone by rollback, and which handled by recorded compensation? → A: Option A: the specification states a two-class rule (reversible operations are rolled back; irreversible operations use recorded compensating action). Irreversible by definition: audit events, issued short codes, recorded approvals and decisions, analytics events already recorded, and anything released, tagged, or published. An attempted rollback of an irreversible operation is rejected and audited. Per-stage classification of all other operations is deferred to the plan and ADRs (D-002).
- CL-5 — Q: What are the allowed release-readiness outcomes, and may a release be recommended with open limitations? → A: Option A: three outcomes: READY, READY WITH ACCEPTED LIMITATIONS, NOT READY. The middle outcome applies only when every open limitation is individually approved by the human candidate and recorded with approval fields, no mandatory policy fails, and no non-waivable constitutional principle (II, III, VIII, X, XI) is among the limitations. Any critical gap gives NOT READY. The system only recommends; the release decision stays human-owned. INCOMPLETE is a possible future enhancement (A-012, backlog).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - API consumer creates and resolves short links (US-001) (Priority: P1)
An API consumer submits a long URL, receives a short code, and uses the short link to reach the original destination. This is the minimum independently deliverable slice: if only this story is implemented, the service still delivers value.
**Why this priority**: Without working create-and-redirect behavior there is no demonstration domain for the orchestration system; every other story depends on this capability existing.
**Independent Test**: Can be fully tested by creating a link for a valid HTTP(S) URL, then resolving the returned short code and verifying the consumer reaches the original destination.
**Acceptance Scenarios**:
1. **Given** a request containing a valid, well-formed HTTP(S) URL, **When** the consumer submits it for shortening, **Then** the system returns a short code that resolves to the submitted URL while the link is active.
2. **Given** an active short link, **When** the consumer requests resolution of its short code, **Then** the consumer is directed to the original target URL and a redirect event is recorded for analytics.
3. **Given** a request containing an invalid URL (malformed, non-HTTP(S) scheme, or exceeding accepted input limits), **When** the consumer submits it, **Then** the system rejects the request with a defined error response and allocates no short code.
4. **Given** a link whose expiration time has passed, **When** the consumer requests resolution, **Then** the system returns the defined expired-link response and does not direct the consumer to the target.
5. **Given** two concurrent, independently issued creation requests with distinct request identities, **When** both are processed, **Then** each receives a distinct short code and both resolve correctly (no code collision or cross-linking). **Given** concurrent retries sharing one request identity, **When** they are processed, **Then** exactly one link exists afterward and every retry receives the same result (Clarification Q2: B).
6. **Given** a duplicate submission of one logical creation request (for example, a client retry carrying the same request identity), **When** it is processed, **Then** the system returns the same short link with no second link created and no double-counted analytics. **Given** an independently issued creation request for the same destination URL, **When** it is processed, **Then** the system creates a new, separate link (Clarification Q2: B).
**Evidence required**: executed test output covering scenarios 1-6, including the negative scenarios 3 and 4.

### User Story 2 - Human reviewer approves, rejects, and governs workflow execution (US-002) (Priority: P2)
A human reviewer inspects a running workflow at mandatory approval checkpoints, approves or rejects high-impact actions, and observes that unapproved actions never progress.
**Why this priority**: Controlled autonomy and human governance are core assessment requirements; without this story the orchestration is ungoverned automation.
**Independent Test**: Can be fully tested by driving a workflow to an approval checkpoint, verifying progression is blocked before approval, then approving and verifying progression resumes from the correct state.
**Acceptance Scenarios**:
1. **Given** a workflow stage that requires human approval has completed its work products, **When** the stage reaches its exit gate, **Then** the workflow pauses and presents the approval request with sufficient context (affected artifacts, decision rationale, policy results) for a human decision.
2. **Given** a pending approval, **When** no response has been given, **Then** the workflow does not progress and the lack of response is not interpreted as approval.
3. **Given** a pending approval, **When** the reviewer rejects it, **Then** the affected path follows the defined rejection behavior (bounded rework or governed termination) and the rejection, reason, and downstream effects are recorded.
4. **Given** a pending approval, **When** the configured approval timeout elapses, **Then** the approval becomes escalated and is re-issued once with a second timeout window of the same length and an audit event; **When** the second window also elapses with no response, **Then** the affected path enters safe-stop, never silent progression (Clarification CL-2).
5. **Given** an attempted high-impact action without a recorded approval, **When** the workflow attempts to progress past the gate, **Then** the progression is blocked and the attempt is recorded in the audit trail.
6. **Given** an approval request raised under a requester's key identity, **When** the same key identity (or an agent actor) attempts to approve it, **Then** the system returns 403, the approval remains pending, and the attempt is recorded in the audit trail (FR-046). **Given** a request without a valid key, **Then** the system returns 401; **given** a valid key without the required role, **Then** 403; both are audited (FR-045).
**Evidence required**: audit records showing approval, rejection, timeout, escalation, self-approval-denied, and blocked-progression events with actor type, action, timestamp, affected artifact/state, result, and reason.

### User Story 3 - Software engineer introduces a brownfield change (US-003) (Priority: P3)
A software engineer submits an enhancement, refactor, or defect correction against the existing URL shortener and observes governed impact analysis before any code changes.
**Why this priority**: Brownfield reasoning is an explicit assessment deliverable; it demonstrates decomposition and change safety against an existing system.
**Independent Test**: Can be fully tested by submitting a brownfield change request and verifying the produced impact analysis covers components, interfaces, data flows, tests, documentation, regression risks, and rollout/rollback considerations before implementation begins.
**Acceptance Scenarios**:
1. **Given** a change request against the existing URL shortener, **When** the workflow ingests it, **Then** the system identifies impacted components, interfaces, data flows, tests, and documentation before changing code.
2. **Given** the impact analysis, **When** decomposition proceeds, **Then** tasks carry explicit dependencies on the impacted areas and regression risks are recorded.
3. **Given** a change whose rollback is not technically possible (e.g., an external side effect has occurred), **When** failure is detected, **Then** the system applies the defined compensation behavior rather than pretending to roll back, and the distinction is recorded. **Given** an attempted rollback of an operation classed as irreversible (FR-027), **Then** the rollback is rejected and audited, and the recorded compensating action is applied instead (FR-049).
4. **Given** a brownfield change that violates an approved requirement or policy, **When** policy evaluation runs, **Then** the change is blocked from progression until it passes or receives an approved, recorded exception.
**Evidence required**: impact-analysis artifact and traceable execution evidence for one demonstrated brownfield change (see SCN-B).

### User Story 4 - Release owner obtains a release-readiness determination (US-004) (Priority: P4)
A release owner requests a release-readiness outcome and receives a deterministic decision backed by policy evaluation results, evidence, and recorded approvals.
**Why this priority**: The lifecycle must terminate in a defensible go/no-go outcome; without it, execution evidence has no decision value.
**Independent Test**: Can be fully tested by completing a workflow to the release-readiness stage and verifying the outcome is one of a defined set of determinate results with traceable justification.
**Acceptance Scenarios**:
1. **Given** a workflow whose mandatory policy checks have all passed and whose evidence is complete, **When** release readiness is evaluated, **Then** the system produces a READY recommendation with the evaluated policy version recorded; the release decision itself is a human decision that the system must record — the recommendation is never self-executing.
2. **Given** a mandatory policy check that failed, **When** release readiness is evaluated, **Then** the outcome is NOT READY, progression is blocked, and the failing check is identified.
3. **Given** a mandatory policy check with an unapproved or expired exception, **When** release readiness is evaluated, **Then** the outcome is NOT READY and the exception state (unapproved or expired) is identified.
4. **Given** a policy exception approved by the authorized human, **When** release readiness is evaluated, **Then** the exception record (policy, reason, scope, approving authority, compensating control, timestamp, expiry/review condition) is included in the decision evidence.
5. **Given** open limitations that are each individually approved by the human candidate and recorded with approval fields, no failed mandatory policy, and no non-waivable constitutional principle (II, III, VIII, X, XI) among them, **When** release readiness is evaluated, **Then** the outcome is READY WITH ACCEPTED LIMITATIONS and the approved limitations are listed in the decision evidence (Clarification CL-5).
6. **Given** an open limitation that is unapproved, or that concerns a non-waivable constitutional principle, or any other critical gap, **When** release readiness is evaluated, **Then** the outcome is NOT READY and the blocking item is identified.
7. **Given** the release-readiness stage, **When** the final engineering summary is produced, **Then** it includes plan/rationale, artifacts, risks/trade-offs/validation, assumptions, and limitations.
**Evidence required**: release-readiness decision artifact for at least one workflow run, including policy outcomes (PASS / FAIL / EXCEPTION-REQUESTED / NOT-APPLICABLE) per applicable check and exactly one release-readiness outcome (READY / READY WITH ACCEPTED LIMITATIONS / NOT READY).

### User Story 5 - Assessment reviewer reconstructs execution from audit evidence (US-005) (Priority: P5)
An assessment reviewer inspects workflow history and audit records to reconstruct what happened, why, and on what authority, without access to the developers' memories.
**Why this priority**: Auditability and traceability are evaluation criteria; this story validates that the system's evidence is sufficient for independent review.
**Independent Test**: Can be fully tested by taking a completed run identifier and reconstructing its full decision lineage (states, decisions, approvals, retries, failures, replanning, terminal outcome) solely from persisted records.
**Acceptance Scenarios**:
1. **Given** a completed workflow run identifier, **When** the reviewer queries its history, **Then** all state transitions, decisions, approvals, retries, failures, replanning events, and terminal outcomes are retrievable.
2. **Given** any recorded audit event, **When** the reviewer inspects it, **Then** it contains actor type, action, timestamp, affected artifact or state, result, and reason.
3. **Given** demonstration metrics (success rate, retry/rollback frequency, MTTR, end-to-end latency), **When** the reviewer reads them, **Then** they are clearly distinguished from production measurements.
4. **Given** a requirement identifier, **When** the reviewer traces it, **Then** the requirement is traceable to its scenarios, tasks, implementation, tests, validation, documentation, and evidence (bidirectional traceability).
5. **Given** a run that entered safe-stop, **When** the reviewer reconstructs it, **Then** the safe-stop cause, the state preserved at stop, and the subsequent resumption (if any) are all recorded.
6. **Given** a run's hash-chained audit events in which one event has been altered, removed, or reordered, **When** the integrity verification check runs, **Then** it reports the run as failing verification and identifies the affected position (FR-048). **Given** an unaltered run, **Then** verification passes.
**Evidence required**: one end-to-end traceability matrix or equivalent linking requirements to evidence, and a queryable audit history for at least one full run.

### Demonstration Scenarios (SCN-A, SCN-B, SCN-C)

**Scenario SCN-A: Greenfield (well-defined requirement)**
1. **Given** a new, well-defined requirement that is complete, consistent, testable, and within approved policy and architecture boundaries, **When** it enters the workflow, **Then** it proceeds through requirement ingestion, decomposition, design, implementation, testing, documentation, validation, and release-readiness without an artificial clarification gate.
2. **Given** the greenfield run, **When** it completes requirement-quality checks, **Then** the workflow records the checks performed and the reason clarification was not required.
3. **Given** the greenfield run, **When** decomposition and design complete, **Then** the resulting API and schema impacts, acceptance criteria, and traceable execution evidence are recorded.
4. **Given** material ambiguity discovered mid-run on one path, **When** it is detected, **Then** only the affected path is suspended, governed clarification and impact analysis are invoked, and the run resumes from the correct state after explicit approval.

**Scenario SCN-B: Brownfield (enhancement/refactor/defect)**
1. **Given** a change request against the existing URL shortener, **When** the workflow starts, **Then** impact identification (components, interfaces, data flows, tests, documentation, regression risks, rollout/rollback considerations) precedes any code change (see US-003).

**Scenario SCN-C: Ambiguous requirement**
1. **Given** an incomplete, unclear, or conflicting requirement, **When** it enters the workflow, **Then** the system detects the ambiguity and prevents unsafe implementation (no implementation work product is produced from the ambiguous input).
2. **Given** detected ambiguity, **When** the workflow requests human clarification, **Then** the request, the human decision, and the resulting downstream impact are recorded.
3. **Given** an approved clarification, **When** the workflow resumes, **Then** affected artifacts are updated and the run resumes at the correct state (not from the beginning).

### Edge Cases
- Short-code collision: two concurrent generations produce the same candidate code — the system must detect and resolve without data loss or cross-linking.
- Persistence failure during redirect handling: the consumer must still receive a correct response or a defined failure, and analytics loss must be bounded and recorded rather than silently dropped.
- Workflow interruption between stages (process restart, host failure): durable state must allow controlled resumption without duplicate side effects.
- Approval timeout while dependent parallel paths wait: synchronization must not deadlock; the approval is escalated once and then the affected path enters safe-stop (FR-024); independent parallel paths proceed to the synchronization point, which then waits for resolution of the stopped path.
- Self-approval attempt, or approval attempted by an agent actor: rejected with 403 and audited (FR-046).
- Rate limit exceeded on creation, redirect, control-plane, or failed-authentication paths: request rejected with 429, no short code allocated, event audited (FR-047).
- Attempted rollback of an irreversible operation: rejected and audited; compensation applies (FR-027, FR-049).
- Retry exhaustion on a failing stage: the system must reach a deterministic terminal outcome (fallback, compensation, or safe-stop), never an infinite retry loop.
- Upstream artifact changes while downstream stages are executing: replanning must re-validate downstream artifacts, evidence, and approvals rather than assuming they remain valid.
- Duplicate creation requests arriving concurrently: idempotency must hold under race conditions.
- Expiration race: a link expires between the resolution request and the response — the defined expired-link behavior must win and be recorded consistently.
- Malicious input: URLs targeting internal addresses, non-HTTP(S) schemes, or oversized payloads must be rejected by validation, and abuse must be contained by rate limiting (see NFR-001).

## Requirements *(mandatory)*

### Functional Requirements

#### URL Shortener Service

- **FR-001** [Confirmed]: System MUST accept a request to create a short link from a valid, well-formed URL and return a short code that resolves to the original URL.
- **FR-002** [Confirmed]: System MUST reject invalid URLs (malformed, disallowed scheme, or exceeding accepted input size) with a defined error response and MUST NOT allocate a short code for rejected requests.
- **FR-003** [Confirmed]: System MUST restrict acceptable URL schemes to a defined set. The specific HTTP/HTTPS-only set is recorded as A-007 and was approved 2026-10-18 (Human Gate 2 decision); any further restrictions MUST be documented as approved assumptions.
- **FR-004** [Derived]: System MUST never reassign a short code to a different target, including after the original link expires, so an expired short link can never silently redirect to an unrelated destination. This safety policy was retained and approved by the human candidate on 2026-10-18.
- **FR-016** [Confirmed]: System MUST generate unique short codes and MUST preserve uniqueness and correct target association under concurrent creation.
- **FR-005** [Confirmed]: System MUST resolve an active short code to its target URL and direct the requester to the target.
- **FR-006** [Confirmed]: System MUST exhibit defined expired-link behavior: a link past its expiration time MUST NOT resolve to its target and MUST return the defined expired-link response.
- **FR-007** [Confirmed]: System MUST treat retries of one logical creation request idempotently: repeated submission carrying the same request identity MUST return the same short link without corrupting state or double-counting analytics. Each independently issued creation request MUST create a new, independent link, even when targeting the same destination URL (Clarification Q2: B, decided 2026-10-18).
- **FR-008** [Confirmed]: System MUST handle concurrent requests correctly: concurrent creations, concurrent resolutions, and concurrent duplicate submissions MUST each produce results equivalent to some serial order (no lost updates, no cross-linking).
- **FR-009** [Confirmed]: System MUST handle persistence failures in a defined manner: failures MUST be classified (transient/permanent), transient failures MAY be retried within bounded limits, and exhausted retries MUST produce a defined error without partial or incorrect redirects.
- **FR-010** [Confirmed]: System MUST expose operational health information sufficient to determine whether the service is up and its critical dependencies are reachable.
- **FR-011** [Confirmed]: System MUST support per-link expiration: a creator MAY specify a lifetime or expiration time; when none is specified, a configurable default TTL MUST apply (default value in A-006, approved 2026-10-18) (Clarification Q3: B, decided 2026-10-18).
- **FR-012** [Confirmed]: System MUST capture a redirect analytics record synchronously as part of redirect handling; analytics reporting MAY be eventually consistent; analytics MUST be retained for the assessment review period (Clarification Q1: A, decided 2026-10-18). The record MUST include the attempt outcome (redirected, expired, not-found), count, and timestamp; analytics reporting MUST distinguish successful redirects from other outcomes; the system MUST NOT record personal information beyond what is approved as an assumption.
- **FR-013** [Derived]: System MUST normalize and validate external input before persistence (leading/trailing whitespace, canonical form) so that identical targets are stored in a single canonical form and request-identity comparison is reliable.
- **FR-014** [Derived]: System MUST return a defined not-found response for an unknown or deleted short code and MUST NOT redirect the requester anywhere.
- **FR-015** [Derived]: System MUST handle analytics capture failure with a defined, observable outcome: when synchronous capture fails, the redirect response MUST still be served, the failure MUST be recorded, and any unrecorded events MUST be quantifiable rather than silently dropped. This failure policy was approved by the human candidate 2026-10-18 (Human Gate 2 decision); Clarification Q1: A selected synchronous capture but did not select the strictly durable per-event guarantee.

#### Agentic SDLC Orchestration

- **FR-020** [Confirmed]: System MUST transform an ingested requirement into a workflow with an explicit dependency graph — not a fixed linear sequence — supporting sequential paths, parallel paths, branching, and synchronization points.
- **FR-021** [Confirmed]: System MUST enforce stage entry criteria and exit criteria for every stage: each stage MUST define inputs, outputs, ownership, and failure behavior; a stage MUST NOT begin before its entry criteria hold.
- **FR-022** [Confirmed]: System MUST persist workflow state durably such that any run can be inspected, interrupted, and resumed.
- **FR-023** [Confirmed]: System MUST preserve cross-stage context and decision lineage: the reasoning behind each decision, the artifacts it produced, and its provenance MUST be retrievable for the life of the run.
- **FR-024** [Confirmed]: System MUST enforce human approval checkpoints for high-impact actions. Covered actions MUST include at minimum: start of implementation, changes to approved requirements/architecture/schemas/workflow states, policy exceptions, acceptance of dynamic replanning, and release-readiness decisions. Each approval record MUST have defined states (pending, approved, rejected, timed-out/escalated); progression from pending MUST be impossible; the approval authority is the human candidate (per the execution guide's tooling decision), acting in the reviewer/approver role; rejection MUST lead to bounded rework or governed termination; on timeout the approval MUST become escalated and be re-issued once with a second timeout window of the same length and an audit event, and if the second window also elapses without response the affected path MUST enter safe-stop (Clarification CL-2: B). Approval timeouts and rejections are non-retryable. Lack of response MUST NOT be interpreted as approval (see US-002). Known limitation: escalation re-notifies the same single human authority (A-010).
- **FR-025** [Confirmed]: System MUST apply bounded retries with defined backoff and timeout behavior for transient failures, and MUST distinguish transient from permanent failures. Approval timeouts and rejections are non-retryable (Clarification CL-2). Retry eligibility for other failure types is deferred (D-001).
- **FR-026** [Confirmed]: System MUST support fallback behavior when a primary approach fails within its bounds: when a stage's primary path fails, the workflow MUST either execute an alternative approach recorded in advance, or transition to the stage's defined failure behavior (FR-025, FR-028) — never retry without limit and never silently skip the stage.
- **FR-027** [Confirmed]: System MUST distinguish rollback from compensation: reversible operations MUST be rolled back; operations that are irreversible MUST use recorded compensating action; the chosen recovery MUST be recorded. The following are irreversible by definition: audit events, issued short codes, recorded approvals and decisions, analytics events already recorded, and anything released, tagged, or published. Classification of all other operations is deferred to the plan and ADRs (D-002) (Clarification CL-4: A).
- **FR-028** [Confirmed]: System MUST enter safe-stop when continuation is unsafe, preserving deterministic terminal state, and MUST support controlled resumption after recoverable interruption without duplicate effects; resumption MUST be authorized under recorded conditions (the safe-stop cause addressed, plus human approval where the stop involved a governed decision).
- **FR-029** [Confirmed]: System MUST dynamically re-plan when upstream outputs change, re-validating affected downstream artifacts, evidence, and approvals, while preserving governance during replanning.
- **FR-030** [Confirmed]: System MUST record audit-grade execution evidence for every run: a correlation/run identifier, state transitions, decisions, approvals, retries, failures, replanning events, and terminal outcomes (see US-005).
- **FR-031** [Confirmed]: System MUST expose reliability and orchestration measurements: success rate, retry/rollback frequency, mean time to recover (MTTR), and end-to-end latency, clearly distinguished as demonstration metrics. MTTR MUST be calculated as total recovery duration across recovered failure events divided by the number of recovered failure events; unrecovered failures MUST be reported separately and excluded from the denominator (Constitution v1.0.0, Evidence and Integrity Constraints).
- **FR-032** [Confirmed]: System MUST identify the versioned policy guardrail set evaluated for every run (security, compliance, privacy, audit retention, approved dependencies, licensing, change control), and each applicable policy check MUST produce exactly one outcome: PASS, FAIL, EXCEPTION-REQUESTED, or NOT-APPLICABLE.
- **FR-033** [Confirmed]: A failed mandatory policy check MUST block downstream workflow progression, and an exception MUST require explicit human approval recorded with applicable policy, reason, scope, approving authority, compensating control, approval timestamp, and expiry/review condition.
- **FR-034** [Confirmed]: System MUST perform requirement-quality checks on ingested requirements: a well-defined, complete, consistent, testable requirement within approved boundaries MUST proceed without an artificial clarification gate, with the checks and the no-clarification rationale recorded (SCN-A).
- **FR-035** [Confirmed]: System MUST detect material ambiguity in ingested requirements, prevent unsafe implementation, request human clarification, record the decision, identify downstream impact, update affected artifacts, and resume at the correct state (SCN-C).
- **FR-036** [Confirmed]: System MUST decompose requirements into actionable tasks with explicit dependencies and sequencing.
- **FR-037** [Confirmed]: System MUST produce a release-readiness determination that fails if any mandatory compliance or change-control policy is violated or has an unapproved or expired exception (see US-004). The determination MUST be exactly one of: READY, READY WITH ACCEPTED LIMITATIONS, NOT READY. READY WITH ACCEPTED LIMITATIONS applies only when every open limitation is individually approved by the human candidate and recorded with approval fields, no mandatory policy fails, and no non-waivable constitutional principle (II, III, VIII, X, XI) is among the limitations; any critical gap yields NOT READY. The system only recommends; the release decision is human-owned (Clarification CL-5: A).
- **FR-038** [Confirmed]: System MUST generate a final engineering summary containing plan/rationale, artifacts, risks/trade-offs/validation, assumptions, and limitations.
- **FR-039** [Confirmed]: System MUST embed policy guardrails for security, compliance, and change control in the orchestration path such that no stage bypasses them.
- **FR-044** [Confirmed]: System MUST NOT declare a task or feature complete unless all Constitution XI (Evidence-Based Completion) conditions hold: applicable requirements identified; decisions and assumptions recorded; acceptance criteria satisfied; required tests executed successfully; security and reliability checks complete; documentation current; traceability complete; residual risks and limitations disclosed; required approval recorded; evidence available for reviewer verification. A completion declaration with failed or unexecuted required validation, incomplete traceability, stale documentation, missing required approval, or unavailable evidence MUST be blocked. This completion gate is distinct from the human release authorization in US-004: the system enforces the completion conditions; the human candidate owns the final release decision.

#### Access Control, Abuse Containment, and Audit Integrity (Clarifications CL-1, CL-3, CL-4)

- **FR-045** [Confirmed]: Redirect resolution MUST be public. Link creation MUST be open without per-consumer accounts (A-002). Orchestration, workflow-inspection, and approval endpoints MUST require a role-scoped key for one of the roles engineer, approver, release owner, or reviewer. A request without a valid key MUST be rejected (401); a valid key lacking the required role MUST be rejected (403); both MUST be audited. Keys are a local-prototype mechanism and not production identity (A-009), and MUST NOT appear in logs (NFR-001).
- **FR-046** [Confirmed]: Self-approval MUST NOT be allowed: the approver key identity MUST differ from the requester's key identity, and a self-approval attempt MUST return 403, leave the approval pending, and be audited. Agent actors MUST NEVER approve their own decisions.
- **FR-047** [Confirmed]: System MUST apply per-IP rate limiting to link creation and redirect resolution, per-key rate limiting to control-plane endpoints, and per-IP limiting of failed authentication, using the limits in A-008a-d. A request over a limit MUST be rejected with a rate-limit response (429) and a retry hint, MUST NOT allocate a short code, and MUST be audited and counted as a demonstration metric. Limits MUST be configurable.
- **FR-048** [Confirmed]: Audit and workflow records MUST be retained through the assessment review period (A-005). The application MUST expose no update or delete operation on them. Each run's audit events MUST be hash-chained, and a verification check MUST detect altered, removed, or reordered events. No purge exists in the prototype; any purge is a destructive action requiring explicit human approval. Hash-chaining detects tampering but does not prevent it for someone with direct storage access (A-011).
- **FR-049** [Confirmed]: An attempted rollback of an operation classed as irreversible (FR-027) MUST be rejected and audited, and the recorded compensating action MUST be applied or proposed for approval as the recovery.

#### Demonstration Scenarios

- **FR-040** [Confirmed]: System MUST demonstrate Scenario SCN-A (greenfield) end-to-end with recorded requirement-quality checks, decomposition, API and schema impacts, acceptance criteria, and traceable execution evidence.
- **FR-041** [Confirmed]: System MUST demonstrate Scenario SCN-B (brownfield) with impact identification preceding change, per US-003 and SCN-B.
- **FR-042** [Confirmed]: System MUST demonstrate Scenario SCN-C (ambiguous requirement) with blocked unsafe implementation, governed clarification, recorded decision, artifact updates, and correct-state resumption.
- **FR-043** [Derived]: System MUST demonstrate at least one execution containing genuinely parallel paths with a synchronization point, to prove non-linear orchestration rather than a linear chain.

### Non-Functional Requirements

Each NFR states the area's verifiable expectation. Where the assignment provides no numeric target, a validation target (PVT) is recorded; all PVTs were approved by the human candidate on 2026-10-18 (Human Gate 2 decision) and remain refinable at Stage 3 clarify through the governed change process.

- **NFR-001 Security**: The system MUST validate and normalize external input, restrict URL schemes (FR-003), address malicious-redirect and abuse scenarios (rate limiting, internal-address targeting rejection), keep secrets and sensitive information out of logs, apply secure configuration defaults and least privilege with explicit trust boundaries, and include dependency and secret scanning in release readiness. Authentication assumptions are documented (A-002, A-008, A-009; FR-045 to FR-047).
- **NFR-002 Reliability**: The system MUST classify transient versus permanent failures, use bounded retries with defined backoff and timeouts, apply idempotency to repeatable operations, define fallback behavior, and preserve deterministic terminal outcomes (FR-025 to FR-028). Bounds: PVT-004 (approved 2026-10-18).
- **NFR-003 Scalability**: The system MUST remain correct under concurrent load (FR-008) with an approved demonstration target of 100 concurrent creation requests producing correct results (PVT-003). Horizontal scale-out is out of scope for the prototype (C-002).
- **NFR-004 Maintainability**: The system MUST separate domain logic, API delivery, persistence, orchestration, policy enforcement, telemetry, and infrastructure concerns, prefer explicit interfaces for external dependencies, and record material architectural decisions with rejected alternatives (ADR stage).
- **NFR-005 Observability**: The system MUST assign a correlation/run identifier to each orchestration execution and record state transitions, decisions, approvals, retries, failures, replanning events, and terminal outcomes such that logs, metrics, traces, and workflow history support reconstruction of an execution (FR-030, US-005).
- **NFR-006 Auditability**: Audit evidence MUST include actor type, action, timestamp, affected artifact or state, result, and reason; policy outcomes and exceptions MUST be included in audit and traceability evidence (FR-030, FR-032, FR-033). Retention per A-005 and integrity per FR-048, distinct from the analytics retention decision (Clarification Q1: A).
- **NFR-007 Performance**: Demonstration targets (approved 2026-10-18): at least 95% of valid redirect resolutions within 200 ms and at least 99% resolution success for valid active links in the local environment (PVT-001, PVT-002).
- **NFR-008 Recoverability**: The system MUST support controlled resumption after interruption without duplicate effects, reach deterministic terminal outcomes after retry exhaustion, and measure MTTR (FR-028, FR-031; PVT-006).
- **NFR-009 Testability**: The system MUST be verifiable through unit, integration, API contract, orchestration transition, reliability, security, and end-to-end tests, developed red-green-refactor where technically practical (C-003); every requirement in this specification must be testable.
- **NFR-010 Change Safety**: Changes to approved requirements, architecture, schemas, workflow states, security controls, or release criteria MUST pass through formal impact analysis and change approval (C-005, FR-039); versioned policy guardrails MUST be evaluated per run (FR-032).
- **NFR-011 Controlled Autonomy**: Agent autonomy MUST be bounded, observable, and auditable; high-impact actions require explicit human approval (FR-024); mandatory gates MUST NOT be silently skipped and lack of response MUST NOT be interpreted as approval.

### Key Entities *(include if feature involves data)*

- **ShortLink**: the mapping between a validated target URL, its unique short code, creation metadata, effective expiration (explicit, or derived from the configured default TTL per FR-011), and lifecycle status (active/expired).
- **RedirectEvent**: the record of a resolution attempt: link reference, timestamp, outcome (redirected, expired, not-found).
- **Workflow (Run)**: the governed execution of a requirement: run identifier, requirement reference, dependency graph, durable state, evaluated policy version.
- **Stage/Task Node**: a unit of work in the dependency graph: entry criteria, exit criteria, inputs, outputs, ownership, failure behavior, status.
- **Approval**: a human decision record: requester key identity, approver key identity and role (which MUST differ from the requester's, FR-046), decision (approve/reject), scope, timestamp, timeout/escalation state (including the single re-issue), and the artifact(s) it authorizes.
- **AuditEvent**: an immutable execution record: actor type (human/agent/system), action, timestamp, affected artifact or state, result, reason, and an integrity link to the preceding event in its run (FR-048).
- **PolicyEvaluation**: the outcome of one policy check within a versioned guardrail set: check identifier, outcome (PASS/FAIL/EXCEPTION-REQUESTED/NOT-APPLICABLE), and evidence reference.
- **EngineeringSummary**: the terminal work product: rationale, artifacts, risks, trade-offs, validation results, assumptions, limitations.

## Success Criteria *(mandatory)*

### Measurable Outcomes
- **SC-001**: All user stories US-001 through US-005 have executed acceptance evidence, including the negative scenarios.
- **SC-002**: All three demonstration scenarios (SCN-A, SCN-B, SCN-C) complete with traceable evidence linking each stage output to its requirement identifiers.
- **SC-003**: At least one workflow run demonstrates parallel paths with synchronization, a mid-run suspension/resumption, and a dynamic replanning event — proving non-linear, stateful execution.
- **SC-004**: Every attempt to progress past a mandatory human gate without approval is blocked and audited (100% of attempts in demonstration runs).
- **SC-005**: Reliability measurements (success rate, retry/rollback frequency, MTTR, end-to-end latency) are reported for demonstration runs and clearly distinguished from production measurements.
- **SC-006**: A reviewer with only the repository and audit records can reconstruct any demonstration run end-to-end (verified by independent reconstruction in SCN-C/US-005 evidence).
- **SC-007**: Approved validation targets are met in demonstration runs; any deviation from an approved target requires the applicable human acceptance or recorded exception process (FR-033), not documentation alone. Targets were approved by the human candidate on 2026-10-18 (see Validation Targets).

## Assumptions

### Confirmed Requirements
All requirements tagged [Confirmed] above are directly supported by the assignment text, the approved execution guide (Prompt 2), the approved Constitution v1.0.0, or recorded human clarifications and decisions, including: the URL shortener feature set; the orchestrated SDLC stages (realized in the plan as 14 graph nodes N01-N14, two of them conditional) (requirement ingestion through final engineering summary); the orchestration properties (dependency graph, parallelism, state persistence, approval gates, retries, fallback, rollback/compensation, safe-stop, resumption, dynamic replanning, audit evidence, measurements); the three scenarios; the five user types; and the specification rules. The execution guide's tooling decision also confirms that the human candidate is the architecture owner, decision authority, approval authority, reviewer, and final quality owner — control-plane approval and evidence inspection are human-owned functions.

### Derived Requirements
Requirements tagged [Derived] (FR-004, FR-013, FR-014, FR-015, FR-043) are necessary implications of confirmed requirements; FR-004 was explicitly retained as derived by human decision on 2026-10-18, and all derived requirements were confirmed at Human Gate 2 on 2026-10-18.

### Constraints
- C-001: No technology selection (language, framework, database, cloud, agent framework, deployment platform) is made in this specification; selection is a later, approved stage.
- C-002: The system is a production-oriented prototype, locally runnable, demonstrating the SDLC — not a production deployment with SLA-grade availability.
- C-003: Red-green-refactor TDD applies to domain and orchestration behavior wherever technically practical; task completion requires executed validation, not merely generated code.
- C-004: No evidence may be fabricated; demonstration metrics must be distinguishable from production measurements.
- C-005: Changes to approved requirements, architecture, schemas, workflow states, security controls, or release criteria must pass through formal impact analysis and change approval.
- C-006: The work is timeboxed to 2-3 days per the assignment; mandatory validation and reviewer evidence MUST NOT be displaced by optional work (inherited from the approved constitution and assignment; added for traceability 2026-10-18).

### Assumptions (recorded defaults; items approved on 2026-10-18 are marked with the decision)
- A-001: The URL shortener is a single-tenant demonstration service; multi-tenancy is out of scope.
- A-002: Link creation is performed by API consumers without per-consumer accounts for the demonstration scope, with per-IP rate limiting and abuse controls per NFR-001 and A-008; redirects are public; orchestration and approval endpoints require role-scoped keys (FR-045); recorded as an informed default (not a clarification) because a reasonable default exists for a prototype. Approved by the human candidate 2026-10-18 (Human Gate 2 decision); refined by Clarification CL-1 the same date.
- A-003: Redirect analytics capture no personal information beyond what is strictly necessary (approved 2026-10-18, Human Gate 2 decision); analytics are demonstration-grade; capture is synchronous with redirect handling and retention covers the assessment review period (Clarification Q1: A).
- A-004: The system runs locally for demonstration; external network dependencies are minimized and any used ones are documented as trust boundaries.
- A-005: Audit and workflow records for the demonstration are retained for the assessment review period; a formal audit-retention schedule is out of scope for the prototype and is distinct from the analytics retention decision (Clarification Q1: A). Integrity and no-purge rules are in FR-048 (Clarification CL-3).
- A-006: Default link TTL applied when a creation request specifies no expiration (per Clarification Q3: B): 7 days, approved by the human candidate 2026-10-18 (Human Gate 2 decision).
- A-007: Acceptable URL schemes limited to HTTP and HTTPS (referenced by FR-003; approved by the human candidate 2026-10-18, Human Gate 2 decision).
- A-008 (rate limits; proposed by the assistant and approved by the human candidate as assumptions dated 2026-10-18, Clarification CL-1; configurable per FR-047):
  - A-008a: link creation, per IP: 60 requests/min, burst capacity 100 (sized so PVT-003 remains achievable from one IP).
  - A-008b: redirect resolution, per IP: 600 requests/min, burst capacity 200.
  - A-008c: control-plane endpoints, per key: 120 requests/min.
  - A-008d: failed authentication, per IP: 10 failures/min, then temporary block.
  - Shared behavior: over-limit requests receive 429 with a retry hint, allocate no short code, and are audited; the client address is the direct peer address and forwarded headers are untrusted by default.
- A-009 (limitation): role-scoped keys are a local-prototype mechanism and not production identity; in the demonstration the human candidate may hold several keys, but self-approval remains prohibited (FR-046), so a second distinct approver key identity is needed to approve a request raised under another key.
- A-010 (limitation): escalation of a timed-out approval re-notifies the same single human authority (FR-024, E-003); it does not reach a second person.
- A-011 (limitation): hash-chaining of audit events detects tampering but does not prevent it for someone with direct storage access.
- A-012 (backlog): an INCOMPLETE release-readiness outcome (evidence not yet available, distinct from NOT READY) is a possible future enhancement and is not part of this specification.
- D-001 (deferred): retry eligibility for failure types other than approval timeouts and rejections. Impact: stage failure behavior and retry tests. Current assumption: only failures classified transient are retryable, bounded by PVT-004. Owner: human candidate. Decision point: plan stage (reliability design).
- D-002 (deferred): rollback-versus-compensation classification of every operation not named in FR-027. Impact: stage failure behavior, compensation tests. Current assumption: an operation is irreversible unless the plan shows it can be rolled back. Owner: human candidate. Decision point: plan stage and ADRs.
- D-003 (deferred): definition of internal/local address ranges and malicious-redirect rules. Impact: URL validation tests. Current assumption: all loopback, private, and link-local targets are rejected. Owner: human candidate. Decision point: plan stage (security design, threat model).
- D-004 (deferred): temporary-block duration under A-008d, key storage and rotation, and enforcement mechanism for FR-045 to FR-047. Impact: security design and tests. Current assumption: implementation decision, configurable. Owner: human candidate. Decision point: plan stage and the authentication ADR.
- D-005 (deferred): technology selection, deployment and local execution model, and per-stage fallback alternatives. Impact: plan and ADRs. Current assumption: none; excluded by C-001. Owner: human candidate. Decision point: plan stage and ADR gate.
- D-006 (deferred): dynamic-replanning triggers and state-recovery details. Impact: replanning and resumption design, state model, and tests. Current assumption: FR-028 and FR-029 apply as written, and acceptance of a replan requires human approval under FR-024. Owner: human candidate. Decision point: plan stage.

### Ambiguities (resolved 2026-10-18)
Three material ambiguities were identified during specification and resolved by recorded human decisions on 2026-10-18 (answers to clarification questions Q1-Q3). None were silently resolved.
- **AMB-1** — Analytics consistency and retention (FR-012): resolved as Q1: A — synchronous capture as part of redirect handling, eventually consistent reporting, retention for the assessment review period.
- **AMB-2** — Duplicate creation behavior (FR-007): resolved as Q2: B — each independently issued creation request creates a new independent link; idempotency applies to retries of the same request identity.
- **AMB-3** — Expiration default (FR-011): resolved as Q3: B — expiration is optional; a configurable default TTL applies when none is specified (default value in A-006, approved 2026-10-18).

### Human Gate 2 Decision Record
- **Gate**: Human Gate 2 (requirements approval). **Result**: APPROVED.
- **Date**: 2026-10-18. **Authority**: human candidate (approval authority per Constitution III and the execution guide's tooling decision).
- **Decisions recorded**:
  1. Proposed items approved as recorded: A-002, A-003, A-006, A-007, the FR-015 capture-failure policy, Exclusions E-001 to E-005, and Validation Targets PVT-001 to PVT-008.
  2. FR-004's previously bundled requirements were separated: the never-reuse policy remains FR-004 [Derived], as explicitly confirmed by the human candidate; the base uniqueness/concurrency requirement is recorded as FR-016 [Confirmed].
  3. Constitution conformance edits applied: MTTR definition (FR-031), expired-exception coverage (FR-037, US-004 scenario 3), evidence-based completion guard (FR-044).
  4. Template heading reconciliation applied (Key Entities heading) and constraint C-006 (timebox) added.
- **Verification basis**: user-supplied local template heading output and approved Constitution v1.0.0 (ratified 2026-10-18); heading-level comparison only — the template body and installed SKILL.md were not verified in this session.
- **Later stages**: Stage 3 clarify may refine approved assumptions and validation targets only through the governed change process (C-005, FR-039).

### Exclusions (approved by the human candidate 2026-10-18, Human Gate 2 decision)
- E-001: Custom branded domains for short links — excluded from scope.
- E-002: A production deployment pipeline, CDN, and geographic distribution — excluded; local runnable prototype only.
- E-003: End-user account management — excluded (link creation is unauthenticated per A-002; control-plane approval and inspection authority is the human candidate per the execution guide, confirmed in the Confirmed Requirements section; control-plane access uses role-scoped keys per FR-045).
- E-004: Real-money or third-party integrations — excluded.
- E-005: Formal compliance certification (e.g., SOC 2) — excluded; policy guardrails are demonstrated, not certified.

### Validation Targets (approved by the human candidate 2026-10-18 — Human Gate 2 decision; refinable at Stage 3 clarify through the governed change process)
- PVT-001: Redirect resolution success rate for valid, active links: at least 99% of demonstration requests.
- PVT-002: Redirect resolution latency: at least 95% of valid requests resolved within 200 ms in the local demonstration environment.
- PVT-003: Concurrent creation correctness: 100 concurrent, independently issued creation requests (distinct request identities) produce 100 distinct, correctly resolving links; concurrent retries sharing one request identity produce exactly one link.
- PVT-004: Bounded retry: no stage exceeds 3 retry attempts; retry exhaustion always yields a deterministic terminal outcome within 60 s.
- PVT-005: Approval timeout: a pending approval becomes escalated after a configurable timeout (default: 5 minutes in demonstration runs, approved 2026-10-18) and is re-issued once with a second window of the same length; if still unanswered, the affected path enters safe-stop (refined by Clarification CL-2 on 2026-10-18).
- PVT-006: Safe-stop and resumption: an interrupted run resumes from its last durable state with zero duplicate side effects in demonstration runs.
- PVT-007: Audit completeness: 100% of state transitions in demonstration runs are retrievable from audit records with all required fields.
- PVT-008: Workflow success rate and MTTR reported for every demonstration run (no absolute target proposed; trend reporting is the criterion).
