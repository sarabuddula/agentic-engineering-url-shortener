# Human Decision 2026-10-22: governed scope cut of A-018 and SCN-A.4 (C-005, C-006)

Date: 2026-10-22
Approver: Shilpa (human candidate)
Recorded words: "(b) approved", given in reply to a written proposal (prepared by the AI assistant, which does not
approve) offering (a) implement the join-wait bound and the automated timeout scenario test, or (b) a governed scope cut
covered by the real timeout evidence already recorded.

## What is cut
- A-018 (approved 2026-10-19, spec Amendment Log): a synchronization point waits for a stopped path for at most 2
  approval windows, then the synchronization point and run enter SAFE_STOPPED. `app.sync-wait-approval-windows` stays
  configured but is not read by the engine.
- SCN-A.4 (folded from T116 into T089) and US-002 scenario 4 (in T117): mid-run ambiguity on one parallel path; only that
  path suspends at G1 while the independent path proceeds to the synchronization point; safe-stop of the affected path
  while the independent path proceeds.

## Why
In the production graph every gate (G1 at N03, G2 at N07, G7 at N13) is on the single linear path; the only parallel
section (N09 and N10, joined at N11) has no gate. No path can wait for approval while an independent path reaches the
join, so the bound would only be reachable from a test fixture. Both items share this root cause.

## Evidence that does exist (live, evidence class HUMAN)
- SCN-C `run-05c9fd8d-0888-4b45-a1ae-4516f00688c4`: G2 approval 5 expired after one escalation re-issue; the N07 path
  and the run safe-stopped (APPROVAL_TIMEOUT); resumed by demo-approver (APPROVER, not the requester).
- T089 `run-4738769c-4517-4597-b0b9-ee5796670220`: G7 approval 19 expired after one escalation re-issue; the N13 path
  safe-stopped; resumed by demo-approver; G7 approval 20 approved; the run COMPLETED.
- SCN-B `run-8306f656-0866-4e87-b538-e5977c7c4431`: CHANGE_APPROVAL 10 escalated after its first window and was
  answered within the re-issue window.

## Not demonstrated (disclosed)
The join wait, its 2-window bound, and the safe-stop of one path while the independent path proceeds (US-002 scenario 4)
are not demonstrated. Recorded as accepted limitation L-SC-07 (spec Amendment Appendix C), for individual approval at G6
(T093).
