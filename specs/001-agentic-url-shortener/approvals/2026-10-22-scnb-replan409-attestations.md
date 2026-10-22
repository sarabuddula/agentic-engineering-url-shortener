# Human Decisions 2026-10-22: SCN-B approach, /replan 409 body, attestation templates

Date: 2026-10-22
Approver: Shilpa (human candidate)
Recorded words: "1&3 go with recomandations and for 3 give me template to fill in", given in reply to a written
proposal (prepared by the AI assistant, which does not approve) that listed: 1. SCN-B (T069/T070): run the A-006
change (7 days -> 24 hours) on a demonstration branch and record the evidence; main keeps the approved 7-day default.
2. T107 attestations PRV-02, LIC-02, DEP-02 need the candidate's own attestation; a template is provided. 3. Add the
standard Error body to the 409 of POST /runs/{runId}/replan.
Interpretation recorded: "for 3 give me template" is read as the attestation template (item 2); items 1 and 3 follow the
recommendations.
Revision before push (same day): the candidate asked whether the branch approach follows the two guidance documents.
It does not: T069/T070 (generated from them) apply the change to the codebase and update the superseded tests. The
candidate's follow-up decision, "go with the guidelines of the given 2 documents", replaces item 1 below.

## Decisions
1. SCN-B: applied on main as T069/T070 specify - the A-006 default changes from 7 days to 24 hours under CHANGE_APPROVAL
   and G2 DESIGN_APPROVAL decided by the candidate; existing links keep their stored expiry; tests asserting the
   superseded 7-day default are updated and listed in the impact analysis; A-006 gets a recorded spec amendment.
2. T107: templates under docs/attestations/ (PRV-02, LIC-02, DEP-02). The candidate fills them in; nothing is attested
   until then.
3. Contract (C-005, additive): the 409 of POST /runs/{runId}/replan carries the standard Error body (REPLAN_CONFLICT).
