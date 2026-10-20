# Requirements Quality Checklist

**Purpose**: Gate check that the approved specification, clarifications, and their downstream plan coverage are complete, clear, consistent, measurable, and scenario-covered. These are unit tests for requirements, not implementation tests.
**Created**: 2026-10-19
**Feature**: `001-agentic-url-shortener`

**Ownership**: The human candidate owns this checklist and its results; an independent reviewer re-runs it at the formal gate. No item requires executing code.

## Completeness

- [ ] CHK001 — Are all functional requirements FR-001..FR-049 individually testable as written (one observable outcome each)? [Completeness — Spec FR-001..FR-049]
- [x] CHK002 — Are all clarifications CL-1..CL-5 reflected in the requirement text they resolve, with the resolution date recorded? [Consistency — Spec CL-1..CL-5]
- [x] CHK003 — Are all deferred decisions D-001..D-006 resolved in the plan with the resolution recorded where the decision point said it would be? [Completeness — Spec D-001..D-006, Plan]
- [ ] CHK004 — Are assumptions A-001..A-012 each labeled with their approval state (informed default, Human Gate 2 decision, or open limitation)? [Clarity — Spec A-001..A-012]
- [ ] CHK005 — Are the derived requirements (FR-004, FR-013, FR-014, FR-015, FR-043) justified by their parent confirmed requirements and marked as derived? [Consistency — Spec]
- [ ] CHK006 — Does every requirement that lacks a numeric target have a proposed validation target recorded as an assumption requiring approval rather than a confirmed number? [Measurability — Constitution Evidence Constraints]
- [ ] CHK007 — Is every non-functional requirement NFR-001..NFR-011 distinguishable from functional requirements and mapped to at least one validation approach? [Completeness — Spec NFR-001..NFR-011]

## Clarity

- [ ] CHK008 — Is each requirement stated so it has exactly one reading, with no undefined terms? [Clarity — Spec FR-001..FR-049]
- [ ] CHK009 — Are spec-defined terms (request identity, approval, operation, policy check outcome) defined once and used consistently across spec, plan, and contracts? [Consistency — Spec/Plan/contracts]
- [ ] CHK010 — Are MUST/SHOULD/MAY levels used consistently between the spec and the plan sections that enforce them? [Consistency — Spec/Plan]

## Traceability

- [ ] CHK011 — Does every requirement map to at least one plan design element, task, and validation item in the traceability matrix? [Completeness — Plan §13]
- [x] CHK012 — Is the requirement-to-evidence mapping bidirectional (evidence items also resolve back to requirements)? [Traceability — CHG-03, Plan §13]
- [ ] CHK013 — Do the user stories US-001..US-005 each trace to the requirements that realize them? [Traceability — Spec US-001..US-005]

## Acceptance criteria

- [ ] CHK014 — Are the success criteria SC-001..SC-007 each objectively decidable (an independent reviewer can rule pass/fail)? [Measurability — Spec SC-001..SC-007]
- [ ] CHK015 — Does every validation target PVT-001..PVT-008 have a stated measurement method and environment? [Measurability — Spec PVT-001..PVT-008]
- [x] CHK016 — Is the MTTR definition (recovered events only; unrecovered reported separately; governance wait excluded) consistent between spec, plan, and data model? [Consistency — Constitution Evidence Constraints, Plan §7]

## Scenarios

- [x] CHK017 — Does SCN-A cover the greenfield path end-to-end including parallel paths, synchronization, and the single-run SC-003 demonstration variant? [Scenario coverage — Spec SCN-A, FR-043]
- [x] CHK018 — Does SCN-B cover the brownfield path including behavior toward existing code and repository state? [Scenario coverage — Spec SCN-B]
- [x] CHK019 — Does SCN-C cover the ambiguous-requirement path through the clarification gate G1 with a recorded clarification answer? [Scenario coverage — Spec SCN-C, Plan §5]
- [x] CHK020 — Does at least one scenario demonstrate a failed mandatory policy check producing a NOT READY outcome with N13 not taken? [Scenario coverage — FR-033, FR-037, Plan §4]
- [x] CHK021 — Does at least one scenario demonstrate kill-and-restart resumption with zero duplicate effects? [Scenario coverage — PVT-006]
- [x] CHK022 — Does at least one scenario demonstrate approved replanning with an approver distinct from the requester? [Scenario coverage — FR-029, FR-046]
- [x] CHK023 — Are the abuse and edge scenarios (rate limiting, malformed input, expiration race, unknown code) each tied to a requirement and a defined response? [Scenario coverage — Spec, contracts]
- [x] CHK024 — Is the malicious-redirect rule set (D-003 additions: ::, fc00::/7, *.localhost, IPv4-mapped IPv6, decimal/octal IP forms, userinfo URLs, localhost names) fully derived into testable rules? [Completeness — D-003, Plan §8]

## Orphan requirements

- [ ] CHK025 — Does any requirement lack a plan design element, task, or validation item that realizes it? [Gap — Plan §13]
- [ ] CHK026 — Does any plan design element or test exist without a requirement reference (orphan design)? [Gap — Plan §13]

## Fabricated-results prevention

- [x] CHK027 — Does the plan consistently distinguish planned validation from executed validation (nothing described as executed before it is)? [Consistency — Plan §14, Constitution X]
- [ ] CHK028 — Are demonstration metrics labeled DEMONSTRATION in the spec, plan, contract, and quickstart consistently? [Consistency — C-004, FR-031]
- [x] CHK029 — Are simulated inputs, demonstration executions, and mock dependencies labeled as such in scenario definitions? [Clarity — Constitution X]
- [ ] CHK030 — Does the spec-delta list under C-005 record every deviation from approved spec text with its approval status (409 approved; Idempotency-Key header note recorded; 200/201 split, 400 on missing or malformed key and replay-after-expiry recorded; 302/410/base-62 closed by human decision 2026-10-19)? [Completeness — C-005, Plan §2]

## Notes

- Items are answerable from the committed documents (spec.md, plan.md, research.md, data-model.md, contracts/, quickstart.md, adrs.md) without running code.
- Any unchecked item at the gate is a finding against the document set, not against an implementation.
- IDs continue in architecture-orchestration.md (CHK031+).
