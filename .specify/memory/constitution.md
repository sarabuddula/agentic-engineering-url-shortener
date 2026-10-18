# Agentic Software Engineering System - URL Shortener Constitution

## Core Principles

### I. Specification Before Implementation
- No production implementation MAY begin without an approved specification.
- Requirements MUST be testable and traceable. Functional and non-functional requirements MUST remain distinguishable.
- Ambiguities MUST be resolved or recorded as explicit assumptions.
- Material upstream changes MUST trigger downstream impact analysis.
- Existing code MUST NOT become an undocumented substitute for the specification.

### II. Explicit Agentic Orchestration (NON-NEGOTIABLE)
- Orchestration MUST be more than sequential agent chaining. It MUST use an explicit dependency graph or equivalent stateful model.
- It MUST support sequential paths, parallel paths, synchronization, branching, dynamic replanning, interruption, and resumption.
- Every stage MUST define inputs, outputs, entry criteria, exit criteria, ownership, and failure behavior.
- Workflow state, cross-stage context, artifact provenance, and decision lineage MUST be persisted.
- Agent autonomy MUST be bounded, observable, and auditable.

### III. Human Governance
- Humans own requirements, architecture, security-sensitive decisions, material exceptions, destructive changes, release readiness, and final quality.
- High-impact actions MUST have explicit human approval. Approval, rejection, escalation, timeout, and safe-stop behavior MUST be defined.
- Mandatory approval gates MUST NOT be skipped. Lack of response MUST NOT be treated as approval.

### IV. Test-Driven Engineering
- Red-green-refactor TDD MUST be applied to domain and orchestration behavior wherever technically practical.
- A failing test MUST be written first and shown to fail for the expected reason. Implement only the minimum behavior to pass, and refactor only while tests are green.
- The test suite MUST include unit, integration, API contract, orchestration transition, reliability, security, and end-to-end tests.
- Task completion MUST require executed validation, not merely generated code.

### V. Security and Privacy by Design
- External input MUST be validated and normalized. Accepted URL schemes MUST be restricted.
- Malicious redirect, internal/local address, and abuse scenarios MUST be addressed.
- Secrets and sensitive information MUST NOT appear in logs. Secure configuration defaults, least privilege, and explicit trust boundaries MUST be used.
- Authentication assumptions, rate limiting, threat scenarios, and security trade-offs MUST be documented.
- Dependency and secret scanning MUST be part of release readiness.

### VI. Compliance and Change-Control Policy Enforcement
- Versioned policy guardrails MUST cover security, compliance, privacy, audit retention, approved dependencies, software licensing, and change control.
- Every orchestration run MUST record the policy version evaluated.
- Every applicable policy check MUST produce exactly one of: PASS, FAIL, EXCEPTION-REQUESTED, NOT-APPLICABLE.
- A failed mandatory policy check MUST block downstream workflow progression.
- A policy exception MUST have explicit human approval and record: applicable policy, reason, scope, approving authority, compensating control, approval timestamp, and expiry or review condition.
- Changes to approved requirements, architecture, schemas, workflow states, security controls, or release criteria MUST pass formal impact analysis and change approval.
- Release readiness MUST fail if a mandatory policy is violated or has an unapproved or expired exception. Policy outcomes and exceptions MUST appear in audit and traceability evidence.

### VII. Architecture and Maintainability
- Domain logic, API delivery, persistence, orchestration, policy enforcement, telemetry, and infrastructure MUST be separated.
- Explicit interfaces SHOULD be used for external dependencies.
- Material architectural decisions and rejected alternatives MUST be recorded in ADRs.
- Complexity that cannot be justified by requirements or demonstrability MUST be avoided. Code MUST be modular, readable, testable, and replaceable.

### VIII. Reliability and Recovery
- Transient and permanent failures MUST be classified.
- Retries MUST be bounded, with defined backoff and timeout behavior. Repeatable operations MUST be idempotent.
- Fallback behavior and safe-stop conditions MUST be defined.
- Rollback MUST be distinguished from compensation. Where rollback is impossible, compensation MUST be defined.
- Terminal outcomes MUST be deterministic, and interrupted runs MUST be resumable under control.
- Evidence of success, failure, retry, compensation, recovery, and latency MUST be captured.

### IX. Observability and Auditability
- Each orchestration execution MUST have a correlation or run identifier.
- State transitions, decisions, approvals, retries, failures, replanning events, and terminal outcomes MUST be recorded.
- Audit events MUST include actor type, action, timestamp, affected artifact or state, result, and reason.
- Logs, metrics, traces, and workflow history MUST allow reconstruction of an execution.
- Demonstration metrics MUST be clearly distinguished from production measurements.

### X. Traceability and Repository Integrity
- Traceability MUST be maintained across requirement, scenario, decision, design, task, implementation, test, validation, documentation, and evidence.
- Commits MUST be small, logically coherent, and describe engineering intent. Commit history MUST reflect the actual execution sequence.
- Approval, test, and execution evidence MUST NOT be fabricated.
- AI-generated artifacts MUST receive human review and ownership. Documentation MUST evolve with implementation.

### XI. Evidence-Based Completion
A feature or task is complete only when all of the following hold:
1. Applicable requirements are identified.
2. Decisions and assumptions are recorded.
3. Acceptance criteria are satisfied.
4. Required tests have been executed successfully.
5. Security and reliability checks are complete.
6. Documentation is current.
7. Traceability is complete.
8. Residual risks and limitations are disclosed.
9. Required approval is recorded.
10. Evidence is available for reviewer verification.

## Evidence and Integrity Constraints

- AI-generated claims (for example "all workflows support rollback") are not evidence. Acceptable evidence is a requirement, design, implementation, test, executed output, or scenario record.
- Simulated input, demonstration execution, mock dependencies, assumed requirements, and proposed production enhancements MUST be labeled as such.
- A command MUST NOT be reported as executed unless it was executed, and success MUST NOT be reported unless it succeeded.
- MTTR MUST be calculated as total recovery duration across recovered failure events divided by the number of recovered failure events. Unrecovered failures MUST be reported separately and excluded from the denominator.
- Where the assignment gives no numeric target, a proposed validation target MUST be recorded as an assumption requiring approval, not as a confirmed requirement.
- SpecKit is the sole lifecycle methodology. Competing planning, task, memory, or execution frameworks MUST NOT be introduced.

## Development Workflow and Quality Gates

- Lifecycle order: constitution, specify, clarify, plan, ADRs, checklist, tasks, analyze, implement, converge. Human gates follow requirements, architecture, pre-implementation review, scenarios, and release readiness.
- Authority hierarchy when instructions conflict: assignment requirements, constitution, specification and clarifications, plan and contracts, accepted ADRs, tasks, current implementation, informal conversation.
- AI assistants MUST NOT approve their own decisions. ADRs become Accepted only by explicit human approval.
- Implementation MUST proceed in bounded, dependency-safe task groups, each followed by a checkpoint review and a pre-commit review before commit.
- If an approved design proves impractical, work MUST stop. The plan or ADR MUST be updated and approved, tasks regenerated, analysis re-run, and only then may work resume.
- Scope control: the work is timeboxed to 2-3 days per the assignment. Mandatory validation and reviewer evidence MUST NOT be displaced by optional work.

## Governance

- Compliance assessment: every plan MUST include a constitution check. Any violation blocks progression until fixed or covered by an approved exception.
- Exceptions: proposed in writing with justification, scope, compensating control, and expiry. Only the human candidate may approve them, and they are recorded in the audit trail.
- Non-waivable principles: II (orchestration), III (human governance), VIII (bounded retries and safe-stop), X (no fabricated evidence), and XI (evidence-based completion).
- Amendments: require a written rationale, impact analysis on dependent artifacts, human approval, and a version bump. MAJOR for removals or redefinitions of principles, MINOR for added or materially expanded guidance, PATCH for clarifications.
- Conflict resolution: the more restrictive of two conflicting principles applies until the human candidate decides. Authority hierarchy above breaks ties between documents.
- Release blocking: non-compliance with any mandatory principle, or an unapproved or expired exception, MUST result in a NOT READY release decision.

**Version**: 1.0.0 | **Ratified**: 2026-10-18 | **Last Amended**: 2026-10-18