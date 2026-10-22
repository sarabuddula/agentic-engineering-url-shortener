# T093 - accepted limitations, individually approved at G6 (FR-037, CL-5)

Run `run-85f0038b-af38-4103-a09c-c201f0235782` (SCN-A requirement, REPLAY), exported in
[runs/run-85f0038b-af38-4103-a09c-c201f0235782.json](runs/run-85f0038b-af38-4103-a09c-c201f0235782.json). The G6 request
endpoint was added for this task (human decision 2026-10-22, approvals/2026-10-22-t093-g6-requests.md).

Each open limitation of spec Amendment Appendix C was requested by demo-engineer and decided individually by
demo-release-owner (RELEASE_OWNER, evidence class HUMAN), who ran the approval commands in their own terminal with
their own wording. Each approval wrote an ACCEPTED_LIMITATION row with its approval reference.

| Limitation | G6 approval | History | Accepted as (the candidate's words) |
|---|---|---|---|
| L-SC-01 | 22 | #50-51 | control-plane rate limiting beyond per-IP limits deferred; demo keys are role-scoped and hashed |
| L-SC-02 | 23 | #53-54 | no dedicated concurrent-resolution test; concurrency covered by PVT-003 |
| L-SC-03 | 24 | #56-57 | staged revision, HELD nodes and replan cap deferred; does not affect a non-waivable principle because every replan stays human-gated |
| L-SC-04 | 25 | #59-60 | rework cycles deferred; rejection ends in governed termination as FR-024(c) permits |
| L-SC-06 | 26 | #62-63 | full role-matrix tests deferred; 401, 403 and self-approval are tested |
| L-SC-07 | 27 | #65-66 | join-wait bound and single-path suspension cut; no gate exists on a parallel path |

L-SC-05 was withdrawn on 2026-10-21 and is not a limitation. The non-waivable-principle determination for L-SC-03
required by Appendix C is the candidate's, recorded in approval 24.

Outcome: N12 15 PASS, 0 FAIL, 0 EXCEPTION_REQUESTED, 1 NOT_APPLICABLE; recommendation READY_WITH_ACCEPTED_LIMITATIONS
(no mandatory check blocked, so no limitation waives a mandatory FAIL); G7 approval 28 by demo-release-owner; run
COMPLETED, terminal outcome READY_WITH_ACCEPTED_LIMITATIONS; audit chain intact (125 events at completion).
