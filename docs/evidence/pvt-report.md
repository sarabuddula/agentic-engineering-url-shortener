# PVT evidence report (T094) - validation targets per demonstration run

Validation targets PVT-001..PVT-008 (spec, approved 2026-10-18), reported for every demonstration run in the
demonstration database. Sources, all committed: the run exports in [runs/](runs/), the executed suite output
[pvt/suite-run-bddb6ba.txt](pvt/suite-run-bddb6ba.txt) (exit 0 at commit bddb6ba), the performance measurement
[pvt/pvt-001-002.json](pvt/pvt-001-002.json) and the aggregate demonstration metrics
[pvt/metrics-demo.json](pvt/metrics-demo.json). Measurement class: DEMONSTRATION (local, single JVM).

## Demonstration runs

| Run | Scenario | Class | Final state | Purpose |
|---|---|---|---|---|
| run-10190a85 | SCN-C | LIVE | SAFE_STOPPED | Found the clarification defect (fixed 90e394a) |
| run-579bebe4 | SCN-C | LIVE | SAFE_STOPPED | G1 and G2 decided; stopped at N08 by the candidate's no-commit instruction |
| run-05c9fd8d | SCN-C | REPLAY | TERMINATED, NOT_READY | CHG-03 failed before the CHG-03 governed change |
| run-934a50a1 | SCN-A | LIVE | SAFE_STOPPED (completion gate) | Release authorized; found two defects (fixed 431fd4c, bfa56f9) |
| run-8306f656 | SCN-B | LIVE | COMPLETED, READY | Brownfield TTL change |
| run-d33a19d6 | SCN-C | REPLAY | COMPLETED, READY | Ambiguous requirement, end to end |
| run-4738769c | SCN-A variant (T089) | REPLAY | COMPLETED, READY | Kill-and-restart, accepted replan, parallel paths |

The first SCN-C run (run-abbb8fc4, stuck at N01 by the run-start defect fixed in 0948f48) is in the repository's
./data database, not the demonstration database, and is not reported here.

## Results per target

| Target | Approved target | Result | Status |
|---|---|---|---|
| PVT-001 | >= 99% redirect success for valid active links | 100% (1000 measured requests, 50 links, after 100 warm-up requests; PerformanceIT) | MET |
| PVT-002 | >= 95% of valid redirects within 200 ms | 100% within 200 ms; p50 2.8 ms, p95 3.6 ms, p99 4.6 ms, max 11.0 ms (PerformanceIT) | MET |
| PVT-003 | 100 concurrent distinct creations -> 100 distinct resolving links; shared-identity retries -> one link | Pvt003ConcurrencyIT 4/4 green; also ArtifactVersionConcurrencyTest for parallel artifact writers | MET (suite) |
| PVT-004 | <= 3 retry attempts; exhaustion -> deterministic outcome within 60 s | 0 retries in every demonstration run (no transient failure occurred); bounded retry and exhaustion covered by ReliabilityTest 7/7 and ReliabilityEngineTest 3/3 | MET (suite); not exercised live |
| PVT-005 | escalation after the timeout, one re-issue, then safe-stop of the affected path | run-05c9fd8d: G2 escalated (#35), expired (#36), safe-stopped, APPROVER resume. run-4738769c: G7 escalated (#109), expired (#111), N13 safe-stopped (#112), APPROVER resume; the superseded G2 (approval 16) also expired (#108, #110) and was correctly ignored. run-8306f656: CHANGE_APPROVAL escalated (#17) and answered in the re-issue window | MET live; join-wait part cut (L-SC-07) |
| PVT-006 | interrupted run resumes from durable state, zero duplicate side effects | run-4738769c: JVM halted after the DESIGN write (fault injection); on restart OPERATION_RECONCILED APPLIED (#19), BOOT_RECOVERY (#22), APPROVER resume; DESIGN v1 written exactly once. KillRestartIT and BootRecoveryOrderingIT green | MET |
| PVT-007 | 100% of state transitions retrievable with all required fields | Every run's audit chain verifies intact; 448 transition events across the seven runs, 0 entries missing seq, actor_type, action, affected, result, reason or occurred_at | MET |
| PVT-008 | workflow success rate and MTTR reported for every run | See the next section | REPORTED |

## PVT-008: workflow success rate and MTTR

Aggregate over the demonstration database (metrics endpoint, measurement class DEMONSTRATION): workflow success rate
0.43 (3 of 7 runs COMPLETED), primary MTTR 382 ms over 3 recovered failure events, MTTR including governance wait
228 s, governance wait in total 683 s, 4 unrecovered failure events, 0 retries, 0 rollbacks, 0 compensations.

Failure episodes per run (node failure to the same node succeeding; this includes the wait for the human, which the
primary MTTR excludes):

| Run | Episodes | Notes |
|---|---|---|
| run-10190a85 | N05 failed, not recovered | the clarification defect; run left safe-stopped |
| run-579bebe4 | N08 failed, not recovered | stopped at N08 by the candidate's no-commit instruction |
| run-05c9fd8d | N08 recovered after 192 s | wait for the implementation commits and review |
| run-934a50a1 | N08 recovered after 404 s | recorded unrecovered by the defect fixed in 431fd4c (this run predates the fix) |
| run-8306f656 | N08 recovered after 534 s | wait for the implementation commits and review |
| run-d33a19d6 | N08 recovered after 103 s | wait for the REPLAY commits and review |
| run-4738769c | N06 recovered after 524 s; N08 after 48 s | N06: JVM halt, restart and APPROVER resume; N08: commits and review |

Every N08 failure is the designed wait for human-reviewed implementation commits, not an engine fault.

## Deviations and how they are handled
- Join-wait bound and single-path suspension (part of PVT-005 context, US-002 scenario 4): governed scope cut,
  accepted limitation L-SC-07, for G6 at T093.
- PVT-004 is not exercised in a live run because no transient failure occurred; it is evidenced by the suite only.
  No exception is requested: the target is a bound, and no run came near it.
- Workflow success rate 0.43: no absolute target exists (PVT-008 is trend reporting). The four runs that did not
  complete are explained in the run table; none failed a validation target.
- run-934a50a1 shows its N08 failure as unrecovered because it ran before the recovery fix; reported as recorded,
  not corrected.
