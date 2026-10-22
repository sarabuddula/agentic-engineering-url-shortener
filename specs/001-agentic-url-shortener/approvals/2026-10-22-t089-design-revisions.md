# Human Decision 2026-10-22: T089 option B - live replan trigger (C-005, additive contract change)

Date: 2026-10-22
Approver: Shilpa (human candidate)
Recorded words: "approve the design", given in reply to a written proposal (prepared by the AI assistant, which does not
approve) for T089 option B, after the candidate chose the recommended order ("yes. go head").

## Problem found
In production, `POST /runs/{runId}/replan` always answered 409: no production stage recorded the artifacts it read
(NODE_INPUT was written only by test fixtures), and no API could record a revised upstream artifact mid-run. Replanning
(FR-029, SC-003) was demonstrated only by fixture-based tests, never in a live run.

## Decision
1. Production stages record their DESIGN reads in NODE_INPUT: N07 (the G2 design gate), N08 (each implementation task)
   and N10 (documentation). Runs without a DESIGN artifact are unaffected.
2. Additive control-plane endpoint `POST /api/v1/orchestrator/runs/{runId}/design-revisions` (ENGINEER), body
   `{ "revision_note": "..." }`: records the next DESIGN version (the previous design plus the note, provenance
   AI_ASSISTED_HUMAN_REVIEWED), audited as DESIGN_REVISED with the key ID. 201 with the new version; 409 DESIGN_NOT_FOUND
   when the run has no design; 400 for a blank note; the contract (openapi.yaml) is extended.
3. T089 is then run live (SCN-A requirement, REPLAY): G2, N08-N12, a design revision and /replan while waiting at G7,
   REPLAN_ACCEPTANCE by an APPROVER other than the requester, re-requested G2, a kill-and-restart (fault injection) with
   an APPROVER resumption, G7, COMPLETED.

Disclosed limitations: a redone N08 finds the existing task commits (markers name run and task, not revision); the
revision used is documentation-only. SCN-A.4 (partial-path G1) and the sync-wait bound (A-018) stay unimplemented.
