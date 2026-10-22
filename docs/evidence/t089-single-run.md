# T089 - SCN-A variant: non-linear execution in one run (SC-003)

Run `run-4738769c-4517-4597-b0b9-ee5796670220`, execution class **REPLAY** (the SCN-A link status lookup was implemented
in run `run-934a50a1-35b3-4b72-85a3-4c66625a9969`, commits 1137e75, 0a78fee, 0bcc30d, 64943ad, 6a0f5c5). Option B of
the human decision 2026-10-22 (approvals/2026-10-22-t089-design-revisions.md). Every approval below was decided by the
human candidate (evidence class HUMAN).

## Mid-run suspension and resumption (kill-and-restart, PVT-006)
- The application was started with `app.fault-injection.halt-after-effect=ARTIFACT_WRITE`; N06 wrote DESIGN v1 and the
  JVM halted (exit 137) before the completion record.
- On restart, boot recovery reconciled the write (`OPERATION_RECONCILED ... APPLIED`: effect found, not re-executed) and
  safe-stopped the run (`BOOT_RECOVERY`). The run was resumed by demo-approver (APPROVER; governed stop), N06 succeeded
  without a second write.

## Approved dynamic replanning (FR-029)
- G2 (approval 16) was requested on DESIGN v1; N07 recorded the version it read.
- demo-engineer recorded DESIGN v2 (`DESIGN_REVISED`): "record that GET /api/v1/links/{code}/status is not rate-limited
  (adding a limit is a security-control change that needs G3; deferred)", and requested a replan: replan 1 staged,
  changed input at N07, affected N07-N14, approval 16 invalidated.
- demo-approver accepted it (REPLAN_ACCEPTANCE approval 17; requester demo-engineer, approver demo-approver):
  revision 1 activated, N07-N14 redone; G2 approval 18 on DESIGN v2 approved.

## Parallel paths with synchronization
N08 fans out to N09 (test execution) and N10 (documentation), joined at N11 (SYNC edges); recorded in the run history.

## Not demonstrated (disclosed)
SCN-A.4 (only one path suspends at G1 while the independent path reaches the synchronization point) and the sync-wait
bound (A-018) are not implemented in the production graph. A redone N08 finds the existing task commits (markers name
run and task, not revision); the revision here is documentation-only.

## Outcome
- N09 and N10 ran in parallel and joined at N11; N12: 16 PASS, 0 FAIL (CHG-02 passed on the replan's re-validation
  records); release recommendation READY.
- G7 approval 19 expired after one escalation (the reply came late; the N13 path safe-stopped, "silence is never
  approval"); demo-approver resumed the governed stop; G7 approval 20 approved by demo-release-owner.
- Run status COMPLETED, terminal outcome READY; terminal audit-chain verification intact (131 events).
