# SCN-A greenfield demonstration (T087) - link status lookup

Requirement (fixture `scn-a.txt`): "Link status lookup: a read-only endpoint returning whether a short code is active,
expired or unknown, with its expiry time (new additive /api/v1 capability; NOT part of the MVP)".

Two runs carry SCN-A: the **LIVE** run that built the capability (run-934a50a1) and the **REPLAY** run that exercised it
end to end with kill-and-restart and replanning (run-4738769c, `t089-single-run.md`). Exports: `runs/`. Every decision
below was made by the human candidate (evidence class HUMAN). Reconstruct either run with
`python3 scripts/reconstruct-run.py docs/evidence/runs/<run>.json`.

## LIVE run run-934a50a1 (101 events, chain verified)

| Step | What happened | History |
|---|---|---|
| N01 intake | requirement stored, provenance PROPOSED | #4 |
| N02 quality | WELL_DEFINED: completeness, consistency, testability, policy fit all PASS | #8 |
| No unnecessary clarification | N03 (G1) and N04 (impact) not selected - NOT_APPLICABLE with the reason "branch not selected by N02" | #10, #11 |
| N05 decomposition | five tasks: t1 contract, t2 domain logic, t3 persistence, t4 tests, t5 documentation | #12-15 |
| N06 design, N07 G2 | design approved by demo-approver (approval 7): "read-only GET /api/v1/links/{code}/status returning ACTIVE/EXPIRED/UNKNOWN with expiry" | #25 |
| N08 implementation | first attempt: no task commits yet, the run safe-stopped (cause FAILURE) - code is never assumed | #37 |
| Implementation, red first | 1137e75 test t4 (LinkStatusTest, LinkStatusApiTest - failing first), 0a78fee contract t1, 0bcc30d domain and controller t2, 64943ad no persistence change t3, 6a0f5c5 quickstart t5 | commits |
| Resume | demo-engineer resumed the failure stop; N08 recorded the five commits as CODE artifacts | #45 |
| N09 tests | the real suite ran (Maven subprocess): 326 test cases, all passed | #75 (exit criteria met); outcomes in `n09-test-outcomes.md` |
| N12 policy | 16 checks; CHG-03 covered by policy exception 1 (G5, approval 8 by demo-release-owner, compensating control: SC-002/003/006/007 are measured on the demonstration runs and recorded by T092/T094); recommendation READY | #39-40, #83 |
| N13 G7 | release authorized by demo-release-owner (approval 9), the CHG-03 exception and the rate-limit limitation disclosed | #90 |
| N14 summary | engineering summary written | #99 |
| Completion gate | COMPLETION_BLOCKED: no evidence for SECURITY_AND_RELIABILITY_CHECKED and TRACEABILITY_COMPLETE; run SAFE_STOPPED | #100-101 |

Why the LIVE run did not reach COMPLETED: (1) its N08 failure was recorded as unrecovered by a defect - a failure
recovered after a resumption stayed unrecovered - found by this run and fixed in 431fd4c (the run predates the fix);
(2) CHG-03 held an approved exception rather than a PASS, and the completion gate requires PASS - the per-run CHG-03
rule was changed afterwards by governed decision (bfa56f9). The completion gate refusing to declare completion on
incomplete evidence is the FR-044 behaviour working as designed.

## Completed run: REPLAY run-4738769c (132 events, chain verified)

The same requirement, implemented by the LIVE run, re-executed end to end (execution class REPLAY, task commits are
REPLAY markers): kill-and-restart during N06 with reconciliation and APPROVER resumption, a design revision with a
replan accepted by an approver other than the requester, parallel N09/N10 joined at N11, all 16 policy checks PASS,
G7 by demo-release-owner after one expiry and resumption, COMPLETED with terminal outcome READY. Details:
`t089-single-run.md`, `t073-replan-acceptance.md`.

## What SCN-A shows and what it does not

- Shows: requirement quality check with no unnecessary clarification; decomposition; API contract impact (additive
  /api/v1, contract commit 0a78fee); acceptance criteria per task; red-first implementation; real test execution;
  policy evaluation; human design and release decisions; traceability (the new tests cite their requirements).
- Does not show: a LIVE greenfield run that reached COMPLETED (the LIVE run stopped at the completion gate for the two
  reasons above; the completed run is REPLAY). SCN-A.4, a gate on one parallel path while the other reaches the join,
  was cut by governed decision (L-SC-07).
