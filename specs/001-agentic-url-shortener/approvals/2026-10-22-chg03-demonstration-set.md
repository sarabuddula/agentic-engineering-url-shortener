# Human Decision 2026-10-22: CHG-03 per run vs the demonstration set (CHANGE_APPROVAL, C-005)

Date: 2026-10-22
Approver: Shilpa (human candidate)
Recorded words: "approve the CHG-03 change", given in reply to a written proposal (prepared by the AI assistant, which
does not approve) titled "Proposed change (C-005): CHG-03 per run vs the demonstration set".

## Problem found (SCN-A demonstration, run-934a50a1-35b3-4b72-85a3-4c66625a9969)
CHG-03 required the whole mapping manifest to be clean. SC-002, SC-003, SC-006 and SC-007 are measured over the
demonstration runs (all three scenarios complete; one run shows the non-linear properties; reviewer reconstruction;
validation targets met), so no single run can supply their evidence before it completes. With the completion gate
(FR-044: COMPLETED requires CHG-03 PASS) no run could ever complete; SC-002 was a true deadlock. A G5 exception
(granted for that run) lets a release be authorized but cannot make traceability complete.

## Decision
1. CHG-03 evaluated per run (N12 policy evaluation and the FR-044 completion gate) checks the mapping manifest with
   SC-002, SC-003, SC-006 and SC-007 excluded from "requirements without evidence"; the evidence text names them as
   deferred to the demonstration-set check. Every other requirement still needs evidence; both directions and the
   integrity checks (files, hashes, commits) must still be clean.
2. The demonstration-set check is unchanged and strict: `scripts/trace-manifest.sh check` (T090) still requires evidence
   for all four. T092 and T094 must record that evidence after the runs; the traceability matrix (T090) is not done
   until that check is fully clean.
3. Recorded as an amendment of the CHG-03 row (data-model.md), a spec amendment note and a scope-control entry.

Not changed: no other policy check; the four criteria are not dropped; existing runs keep their recorded outcomes
(run-934a50a1... stays EXCEPTION_REQUESTED for CHG-03).
