# T092 - Reviewer steps executed end to end (US-005, SC-006)

Executed 2026-10-22 against commit cd56c14, following quickstart.md "Reviewer steps", with only what a reviewer has:
the repository, the reviewer key, and (for step 3) a copy of the demonstration database.

## 1. Clone and build
`git clone` of the repository into a scratch directory, then `mvn clean verify` there: exit 0 in 129 s - 298 unit
tests (Surefire) and 122 integration tests (Failsafe), 0 failures, 0 errors, 0 skipped.

## 2-3. Service and reviewer endpoints for every demonstration run
The cloned build's jar was started on a copy of the demonstration database (`~/demo/data`, copied so the original was
not touched), reviewer key. Results for all eight demonstration runs (status, history length, /verify, release
readiness) agree exactly with the committed exports in `docs/evidence/runs/` - same event counts, every chain
verified, no event added at startup (no run was mid-flight, so boot recovery had nothing to do):

| Run | Status | Terminal outcome | Class | History | /verify | Recommendation | Decision |
|---|---|---|---|---|---|---|---|
| run-05c9fd8d (SCN-C) | TERMINATED | NOT_READY | REPLAY | 108 | verified, 108 | NOT_READY | PENDING |
| run-10190a85 (SCN-C, clarification defect) | SAFE_STOPPED | - | LIVE | 25 | verified, 25 | not determined | - |
| run-4738769c (T089 SCN-A variant) | COMPLETED | READY | REPLAY | 132 | verified, 132 | READY | APPROVED |
| run-579bebe4 (SCN-C, before N08 commits) | SAFE_STOPPED | - | LIVE | 47 | verified, 47 | not determined | - |
| run-8306f656 (SCN-B) | COMPLETED | READY | LIVE | 103 | verified, 103 | READY | APPROVED |
| run-85f0038b (T093 limitations) | COMPLETED | READY_WITH_ACCEPTED_LIMITATIONS | REPLAY | 126 | verified, 126 | READY_WITH_ACCEPTED_LIMITATIONS | APPROVED |
| run-934a50a1 (SCN-A) | SAFE_STOPPED | - | LIVE | 101 | verified, 101 | READY | APPROVED |
| run-d33a19d6 (SCN-C rerun) | COMPLETED | READY | REPLAY | 106 | verified, 106 | READY | APPROVED |

`/api/v1/metrics/demo`: measurement_class DEMONSTRATION; success rate 0.5 over finished runs; MTTR 365 ms (primary,
governance wait excluded) and 244 172 ms including governance wait; 4 recovered and 4 unrecovered failure events;
governance wait 975 229 ms in total.

## 4. Reconstruction from repository artifacts only
`python3 scripts/reconstruct-run.py docs/evidence/runs/<run>.json` (standard library only, no service, no database)
recomputes each run's SHA-256 audit chain from the exported history with AuditChain's canonical form and rebuilds the
run. For all eight runs the recomputed head equals the exported /verify head and the sequences are contiguous.
The SCN-A-variant run (run-4738769c) reconstructs completely - output in `t092-reconstruction-run-4738769c.txt`:
states (all redone nodes SUCCEEDED in revision 1), decisions with evidence class HUMAN (design approval 18, release
authorization 20), approvals including two expiries after escalation, the replan requested by demo-engineer and
accepted by demo-approver, the kill (N06 interrupted, OPERATION_RECONCILED APPLIED, BOOT_RECOVERY, APPROVER resume),
the terminal transition RUNNING -> COMPLETED with outcome READY, and the final CHAIN_VERIFIED event.

## 5. Traceability
`scripts/trace-manifest.sh check` lists SC-006 as the only requirement without evidence before this record; this
record and the reconstruction tool are its evidence (manifest updated in the same change).

## Findings
- quickstart.md: after a hard kill the run is safe-stopped with cause BOOT_RECOVERY, a governed stop, so
  `/resume` needs an APPROVER key (the quickstart showed the ENGINEER key) - corrected in T095.
- run-934a50a1 (SCN-A) shows release recommendation READY with an approved decision but status SAFE_STOPPED: the
  completion gate stopped it (its evidence is in docs/evidence/pvt-report.md); it is not reported as completed.

## Re-run on the final documentation commit (2026-10-22)
The record above ran at cd56c14, before the executor whitelist (3458802, 0bc2f00). Re-run with the same steps:
`git clone` of the repository at 1660823 into a scratch directory; Java 21.0.12.1, Maven 3.9.16, gitleaks 8.30.1.
1660823 was then re-committed as c6dc9f9 with only the commit message changed (tree identical), so these results
hold for c6dc9f9.

1. `mvn clean verify`: exit 0 in 137 s - 302 unit tests (Surefire) and 122 integration tests (Failsafe), 0 failures,
   0 errors, 0 skipped.
2-3. The cloned jar on a copy of the demonstration database (the original was not modified), demo keys generated in
   the clone, reviewer key: `/actuator/health` UP; a control-plane read without a key 401. For all eight runs, status,
   history length, /verify and release readiness are identical to the committed exports and to the table above. The
   two runs without a determination (run-10190a85, run-579bebe4) answer RELEASE_NOT_DETERMINED; their bodies differ
   from the exports only in request_id, which is new per request. `/api/v1/metrics/demo` values are unchanged.
4. `scripts/reconstruct-run.py` on all eight exports: recomputed head equals the exported /verify head for every run.
5. `scripts/trace-manifest.sh check`: CLEAN.

Commands in `docs/reviewer-guide.md`, run as written in the clone, all exit 0: ReliabilityTest and
ReliabilityEngineTest 10 tests; RollbackTest and CompensationTest 6; UrlRejectionMatrixIT and RateLimitIT 35;
OpenApiContractHarnessTest 4; MttrTest 3. Run by Claude Code at the human candidate's direction; results as printed.
