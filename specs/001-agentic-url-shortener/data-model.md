# Data Model: Agentic Software Engineering System — URL Shortener

**Feature Branch**: `001-agentic-url-shortener` | **Date**: 2026-10-18 | **Status**: Phase 1 draft — Human Gate 4 pending

Storage: single H2 file database, both planes, behind repository interfaces (ADR-003). All control-plane rows carry `run_id` (correlation identifier, NFR-005). No table exposes update/delete on audit or workflow records (FR-048).

## Entities

### Application plane

| Entity | Fields |
|---|---|
| **SHORT_LINK** | id (PK), short_code (UNIQUE NOT NULL), target_url (canonical, NOT NULL), request_identity (UNIQUE NOT NULL — idempotency, FR-007), payload_hash (original request, 409 conflict detection; the binding is retained for the life of the row, so a replay after expiry returns the original link with 200), created_at, expires_at (explicit or created_at + default TTL, A-006) — expiry status is **derived lazily at resolution**, not stored |
| **REDIRECT_EVENT** | id (PK), short_link_id (FK, NULLABLE - null for NOT_FOUND events), attempted_code (requested code, length-capped), occurred_at, outcome (REDIRECTED, EXPIRED, NOT_FOUND) — FR-012 |
| **LOSS_REPORT** | id (PK), period_start, period_end, unrecorded_count — FR-015 quantification checkpoint |

### Control plane

| Entity | Fields |
|---|---|
| **WORKFLOW_RUN** | id (PK), requirement_ref, policy_version (FR-032), status, **run_revision** (incremented on accepted replans, max 2 per run; a staged proposal is held in staged_revision; NOT_APPLICABLE rules bind node states to a revision), version (optimistic lock), audit_head_hash, audit_event_count (independent truncation reference), created_at, terminal_outcome, safe_stop_cause (incl. BOOT_RECOVERY), execution_class (LIVE, REPLAY), replan_count |
| **REQUIREMENT_ARTIFACT** | id (PK), run_id (FK), content (normalized), provenance (CONFIRMED, DERIVED, PROPOSED), version, quality_report, assumptions |
| **ARTIFACT** | id (PK), run_id (FK), type (DESIGN, ADR, IMPACT_ANALYSIS, CODE, TEST, DOC, SUMMARY, …), version, content_hash, provenance (DETERMINISTIC, AI_ASSISTED_HUMAN_REVIEWED — Constitution X labeling), created_at — the generic versioned-artifact table referenced by lineage rows |
| **STAGE_NODE** | id (PK), run_id (FK), node_key (N01–N14), status, attempt_count, run_revision (bound at creation), version (optimistic lock), entry_state, exit_state, not_applicable_reason |
| **STAGE_EDGE** | id (PK), run_id (FK), from_node, to_node, kind (DEPENDENCY, SYNC, CONDITIONAL) |
| **NODE_INPUT** | node_id (FK), artifact_id (FK), artifact_version, content_hash_at_read, role (INPUT, EVIDENCE, APPROVAL_REF), recorded_at - composite PK (node_id, artifact_id, artifact_version); records exactly which artifact versions a stage read so a resumed run reconstructs what the stage saw and replanning re-validation compares content_hash_at_read (plan sections 3 and 4) |
| **TASK** | id (PK), run_id (FK), node_id (FK), title, requirement_refs, status, executor_class (DETERMINISTIC, HUMAN_INGESTED) |
| **TASK_DEPENDENCY** | (task_id, depends_on_task_id) composite PK — explicit dependency rows, not a list column (FR-036) |
| **OPERATION_RECORD** | operation_id (PK, stable identity), run_id (FK), node_id (FK), type (ARTIFACT_WRITE, TEST_RUN, DOC_GENERATE, POLICY_EVAL, APPROVAL_REQUEST - each with an effect-verification rule, plan section 3), status (PENDING, COMPLETED, FAILED), effect_verified (bool — set by restart reconciliation), created_at, completed_at — the exactly-once mechanism of plan §3 |
| **FAILURE_EVENT** | id (PK), run_id (FK), node_id (FK), failure_detected_at, recovery_start_at, recovery_complete_at, recovery_mechanism (FP-1, FP-2, FP-3, FP-4), recovered (bool), governance_wait_ms (reported separately per §7), mttr_excluded (bool + reason) — the MTTR capture record of plan §7 |
| **APPROVAL** | id (PK), run_id (FK), gate (G1–G8), requester_key_id, approver_key_id (must differ — FR-046), decision (PENDING, APPROVED, REJECTED, ESCALATED), scope, timeout_at, second_window_timeout_at, decided_at, authorized_artifacts, reason, evidence_class (HUMAN, SIMULATED) |
| **ACCEPTED_LIMITATION** | id (PK), run_id (FK), description, approval_id (FK — individual approval with full fields, CL-5), compensating_control, approved_at — only valid when no mandatory policy failed and no non-waivable principle is implicated (FR-037) |
| **RELEASE_DECISION** | id (PK), run_id (FK, UNIQUE), recommendation (READY, READY_WITH_ACCEPTED_LIMITATIONS, NOT_READY), blocking_reasons, decision (PENDING, APPROVED, REJECTED), approver_key_id (must differ from requester), decided_at, evidence_ref — the system recommends; the decision is human-owned (FR-037) |
| **AUDIT_EVENT** | run_id, seq (UNIQUE(run_id, seq)), actor_type (HUMAN, AGENT, SYSTEM), action, occurred_at, affected_artifact, result, reason, prev_hash, event_hash — append-only |
| **PLATFORM_AUDIT_EVENT** | separate chain for auth failures / rate-limit events outside any run (FR-045/047) — same field set, own sequence |
| **PLATFORM_AUDIT_HEAD** | id (singleton row), head_hash, event_count, updated_at — independent truncation reference for the platform chain |
| **POLICY_EVALUATION** | id (PK), run_id (FK), check_id, outcome (PASS, FAIL, EXCEPTION_REQUESTED, NOT_APPLICABLE), evidence_ref, exception_id (nullable) |
| **POLICY_EXCEPTION** | id (PK), run_id (FK), check_id, reason, scope, approving_authority_key_id, compensating_control, approved_at, expires_at |
| **REPLANNING_EVENT** | id (PK), run_id (FK), status (STAGED, ACCEPTED, REJECTED, EXPIRED), staged_revision, trigger (ARTIFACT_VERSION_CHANGE, APPROVAL_INVALIDATION, POLICY_VERSION_CHANGE), affected_downstream, invalidation_records, approval_id (FK — approver differs from requester, D-006) |
| **DECISION_LINEAGE** | id (PK), run_id (FK), decision, rationale, rejected_alternatives, artifact_version_refs (references ARTIFACT rows and their content hashes) |
| **TEST_OUTCOME** | id (PK), run_id (FK), suite, test_id, requirement_refs, status, executed_at, evidence_ref |
| **RISK_OUTCOME** | id (PK), run_id (FK), risk_desc, outcome, mitigation, requirement_refs |
| **ENGINEERING_SUMMARY** | id (PK), run_id (FK, UNIQUE), content (rationale, artifacts, risks, validation, assumptions, limitations), created_at |
| **KEY_IDENTITY** | key_id (PK), role (ENGINEER, APPROVER, RELEASE_OWNER, REVIEWER), key_hash (SHA-256), label (demo-only) |

## Relationships

- WORKFLOW_RUN 1—n STAGE_NODE, STAGE_EDGE, TASK, TASK_DEPENDENCY, OPERATION_RECORD, FAILURE_EVENT, APPROVAL, ACCEPTED_LIMITATION, RELEASE_DECISION, AUDIT_EVENT, POLICY_EVALUATION, POLICY_EXCEPTION, REPLANNING_EVENT, DECISION_LINEAGE, TEST_OUTCOME, RISK_OUTCOME, ARTIFACT; 1—1 ENGINEERING_SUMMARY, REQUIREMENT_ARTIFACT (current version).
- STAGE_NODE 1—n TASK; TASK 1—n TASK_DEPENDENCY; APPROVAL 1—0..1 REPLANNING_EVENT; APPROVAL 1—0..n ACCEPTED_LIMITATION; POLICY_EXCEPTION 1—0..1 POLICY_EVALUATION. STAGE_NODE 1—n NODE_INPUT; NODE_INPUT n—1 ARTIFACT.
- SHORT_LINK 1—n REDIRECT_EVENT (application plane; cross-plane references by identifier only).

## State transitions

**Run:** RUNNING, AWAITING_APPROVAL, SUSPENDED_CLARIFICATION, SAFE_STOPPED, COMPLETED, TERMINATED. Every transition audited (FR-030). Resumption from SAFE_STOPPED requires recorded conditions + human approval where governed (FR-028). `run_revision` increments on accepted replans; node states bind to the revision they were created under.

**Node:** PENDING → READY → RUNNING → SUCCEEDED | FAILED | NOT_APPLICABLE. FAILED → RETRY_WAITING (transient) → RUNNING; exhaustion → declared failure behavior (fallback, compensation, safe-stop). AWAITING_APPROVAL on human decision. Legal AWAITING_APPROVAL exits: APPROVED recorded -> RUNNING (the executor finalizes with the approval record) -> SUCCEEDED; REJECTED -> FAILED (non-retryable, rework or governed termination); second timeout -> SAFE_STOPPED. Prohibited: PENDING → RUNNING; AWAITING_APPROVAL → SUCCEEDED; any exit from terminal; NOT_APPLICABLE → active on the same run revision (SC-004). HELD (replan staging): PENDING/READY/RETRY_WAITING -> HELD on a staged replan; HELD -> its prior state on rejection or timeout; HELD -> PENDING (re-validated) on acceptance; a HELD node is never dispatched.

**Approval:** PENDING → APPROVED | REJECTED | (timeout) ESCALATED (one re-issue, equal second window, audited) → (second timeout) affected path SAFE_STOPPED. Timeouts and rejections non-retryable (FR-025); silence never approval.

## Failure classification (transient vs permanent)

| Failure | Class | Handling |
|---|---|---|
| Storage connection timeout, worker saturation | Transient | FP-1 retry |
| Operation timeout, optimistic-lock conflict | Transient, retry only after reconciling uncertain effects (OPERATION_RECORD) | FP-1 after reconcile |
| Validation/logic errors, policy FAIL | Permanent | Node failure behavior |
| Approval timeout, rejection | Permanent, non-retryable | Escalation / bounded rework or governed termination |
| Unknown failure | Permanent (default) | FP-4 safe-stop |

## Rollback vs compensation, per stage group (FR-027, D-002)

| Stage group | Operations | Class | Recovery |
|---|---|---|---|
| N01, N02, N05, N06 (deterministic artifact transforms) | Uncommitted transaction inserts (artifact/task rows) | Reversible | Transaction rollback; verify no rows remain — **the one demonstrable rollback case** |
| N03, N07 (human gates) | Approval rows once recorded | Irreversible | Compensation: reversal/superseding approval record; rejected rollback attempts audited (FR-049) |
| N08 (implementation) | Committed code/test files | **Irreversible (reclassified)** — a git revert is a new forward commit, i.e. compensation by definition, not rollback | Compensation: revert commit recorded as the compensating action (never rewrites issued artifacts) |
| N08 (implementation) | Anything tagged/released | Irreversible | Compensation record |
| N09, N10 (testing, documentation) | Outcome rows and docs once recorded/referenced downstream | Irreversible | Superseding record (new version) |
| N11, N12 (evidence, policy) | Policy/evidence rows | Irreversible | Superseding evaluation (new policy version, re-run) |
| All groups | Unclassified operations | Irreversible (default) | Compensation |

## Policy-check inventory (POL-2026-10-18-v1; one outcome each: PASS / FAIL / EXCEPTION-REQUESTED / NOT-APPLICABLE)

| Check ID | Domain | Verifies | Automated / Attestation | Tool |
|---|---|---|---|---|
| SEC-01 | Security | Input validation and HTTP/HTTPS scheme allowlist enforced (contract + unit tests green) | Automated | Test suites |
| SEC-02 | Security | Internal/local address rejection rule set enforced (D-003, incl. approved additions) | Automated | Test suites |
| SEC-03 | Security | No secrets or raw keys in logs or committed files (secret scan + log-content test) | Automated (offline) | gitleaks (local file scan) + log-content test |
| CMP-01 | Compliance | Policy version recorded on the run; every applicable check produced exactly one four-valued outcome | Automated | Engine evaluation |
| CMP-02 | Compliance | Failed-mandatory-check blocking semantics: PASS when no mandatory failure exists in the run, **or** when a failure existed and authorization was verifiably blocked (negative-path evidence); FAIL when a mandatory failure exists without a blocked-authorization record; rollback/compensation coverage (amendment 2026-10-19): additionally PASS only when every FAILURE_EVENT of the run recovered by FP-3 has a recorded compensating action, every rollback attempt on an irreversible operation (FR-049) was rejected and audited, and no operation was recovered by rollback unless classified reversible; FAIL otherwise | Automated | Engine evaluation + forced-FAIL demonstration |
| PRV-01 | Privacy | Analytics fields limited to the approved set; no personal information (field-level check) | Automated | Test suites |
| PRV-02 | Privacy | No PII in logs or analytics beyond the approved minimum | Attestation (human review, recorded) | — |
| AUD-01 | Audit retention | No update/delete API exists on audit/workflow records (API surface test) | Automated | Test suites |
| AUD-02 | Audit retention | Hash-chain verification passes for the run's audit events — evaluated at N12 **and re-verified at N14** (after N13/N14 terminal appends) | Automated | Chain verifier |
| DEP-01 | Approved dependencies | Every runtime dependency pinned and declared in the dependency/license inventory (the forced-FAIL demonstration check); build-time policy tools are themselves listed here | Automated | Build-file scan |
| DEP-02 | Approved dependencies | Dependency vulnerability scan clean | Automated **online**; **offline: degrades to attestation** (last-cached NVD data + recorded degradation note) | OWASP DependencyCheck Maven plugin |
| LIC-01 | Licensing | All dependency licenses are Apache-2.0 or another inventory-approved license | Automated (offline) | license-maven-plugin (reads local dependency POMs) |
| LIC-02 | Licensing | Third-party notices/attribution present in the distribution docs | Attestation (human review, recorded) | — |
| CHG-01 | Change control | Changes to approved artifacts carry approval records (approval-row join) | Automated | Engine evaluation |
| CHG-02 | Change control | Downstream revalidation recorded on replan triggers | Automated | Engine evaluation |
| CHG-03 | Change control | Bidirectional traceability complete (manifest join check; both directions clean) | Automated | Manifest join check |

Runtime tool (fourth governed change 2026-10-21, T128): the N09 testing executor invokes Maven as a local subprocess; Maven is therefore listed in the DEP-01 inventory as a runtime tool, not only a build tool. All checks are mandatory for release; attestations are recorded as human decisions with the same evidence structure (PRV-02, LIC-02; DEP-02 when offline). Build-time tools (gitleaks, OWASP DependencyCheck plugin, license-maven-plugin) are build-scope dependencies, listed in the DEP-01 inventory.
