# T073 - accepted replan with a distinct approver (FR-029, SC-003)

Persistent record of the live replan in T089 run `run-4738769c-4517-4597-b0b9-ee5796670220`. The full run record (run
detail, audit-chain verification, release readiness and the 132-event history) is exported in
[runs/run-4738769c-4517-4597-b0b9-ee5796670220.json](runs/run-4738769c-4517-4597-b0b9-ee5796670220.json); the chain
verified intact (head hash `ab1ddb62d705a13879002834700ce70a10d7393678aa18034efc6662aa7cdc62`).

| Seq | Actor | Action | What it shows |
|---|---|---|---|
| 31-32 | SYSTEM | APPROVAL_REQUESTED G2 (approval 16) | N07 requested G2 on DESIGN v1 and recorded the version it read |
| 34-35 | AGENT, HUMAN | ARTIFACT_RECORDED, DESIGN_REVISED | DESIGN v2 recorded; demo-engineer revised the design (revision note on the status endpoint's missing rate limit) |
| 36-37 | SYSTEM, HUMAN | APPROVAL_REQUESTED REPLAN_ACCEPTANCE, REPLAN_REQUESTED (replan 1, STAGED) | changed input at N07; affected N07-N14; approval 16 invalidated; acceptance approval 17 requested by demo-engineer |
| 38 | HUMAN | APPROVAL_DECIDED REPLAN_ACCEPTANCE APPROVED | approval 17 decided by **demo-approver**, not the requester demo-engineer (FR-046) |
| 39-41 | SYSTEM / HUMAN | REVISION_ACTIVATED, REPLAN_ACCEPTED, DECISION_RECORDED | revision 1 active; N07-N14 redone; the decision is in the lineage |
| 45 | SYSTEM | APPROVAL_REQUESTED G2 (approval 18) | the redone N07 requested G2 on DESIGN v2, superseding approval 16 |

The run then completed (COMPLETED, READY). CHG-02 (replan re-validation records) passed in its N12 evaluation.
