# T084 - Acceptance list for the end-of-Day-2 checkpoint (prepared 2026-10-22)

Prepared with AI assistance for the human candidate's review; acceptance is the candidate's decision. Each task lists
its red commit (failing test, expected reason recorded), its implementation or record commit, and the evidence that
its Validate field ran. Full suite at HEAD: `mvn clean verify` exit 0, 424 tests (302 unit, 122 integration);
traceability check CLEAN.

## Built with a red test first
| Task | What | Red | Done | Validation evidence |
|---|---|---|---|---|
| T067/T068 | Impact analysis at N04 | 3236bba | eb612fe | BrownfieldImpactTest green |
| T071/T072 | Governed replanning | c9b2ea2 | bef1c41 | ReplanningTest, ReplanApiIT green |
| T074/T075 | Retry, fallback, classification | 62ec56d | 4a8f3b5 | ReliabilityTest, ReliabilityEngineTest green |
| T076 | Rollback vs reject-and-compensate | e426d21 | a64bd65 | RollbackTest green |
| T077 | Compensation by revert commit | 8f9eedd | e0adc6c | CompensationTest green |
| T078 | Kill-and-restart (PVT-006) | d831e75 | dd1a494 | KillRestartIT on the jar green; live in run-4738769c |
| T079 | MTTR computation | 77d0dcb | 0f145a3 | MttrTest green |
| T083, T104 | Demonstration metrics, end-to-end latency | c468734 | 54f81d0 | DemoMetricsApiIT green; p95 deferred (reduced) |
| T085 | Run history endpoint | 653efc4 | 6aa7e89 | RunHistoryApiIT reconstructs a fixture run |
| T091 | Engineering summary (N14) | c6f01bb | 3fa580a | EngineeringSummaryTest green |
| T101 | FR-044 completion gate | 5f9b2fb | 960799d | CompletionGateTest, one case per condition |
| T105 | Mapping manifest and checker | 2e4d686 | 958ca17 | MappingManifestTest green |
| T110 | Resume (and replan) endpoints | 31e82b2 | a965aeb | ResumeApiIT and ReplanApiIT (the task names ResumeReplanContractIT; same coverage, two classes) |
| T118 | Decision lineage writer | 05700ed | 2b5e7e6 | DecisionLineageTest green |
| T119 | CHANGE_APPROVAL gate | 231c78d | 7e275a7 | ChangeApprovalTest green; used live in SCN-B |
| T122 | Effect verification, boot recovery | ae08d7c | fb6a094 | BootRecoveryTest green |
| T123 | Failure records survive rollback | c4ff22a | 7a64e09 | FailureRecordingTransactionTest green |
| T127 | Evidence classes visible | f37c01a | 80aebfc | EvidenceClassTest green |
| T128 | Real test execution in N09 | eb23ee5 | 1b4eb44 | TestExecutionIT on the fixture project green |

## Demonstration runs (decisions HUMAN; exports in docs/evidence/runs)
| Task | Run | Record |
|---|---|---|
| T087 SCN-A | run-934a50a1 (LIVE): status lookup built red-first (1137e75..6a0f5c5); N03 not taken (no unnecessary clarification); ended SAFE_STOPPED at the completion gate | pvt-report.md |
| T088 SCN-C | run-05c9fd8d, run-d33a19d6 (REPLAY): suspend at G1, clarification as requirement v2, resume | scn-c-replay.md |
| T069/T070 SCN-B | run-8306f656 (LIVE): A-006 7 days -> 24 hours under CHANGE_APPROVAL and G2; two test expectations changed, both predicted | scn-b-regression.md |
| T089 | run-4738769c (REPLAY): kill-and-restart, approved replan with a distinct approver, parallel N09/N10, READY, COMPLETED | t089-single-run.md; SCN-A.4 part cut (L-SC-07) |
| T073 | replan record of run-4738769c | t073-replan-acceptance.md (ticked by the scenario session) |
| T093 | run-85f0038b: six limitations accepted at G6, READY WITH ACCEPTED LIMITATIONS | t093-accepted-limitations.md (ticked by the scenario session) |
| T094 | PVT-001..008 per run | pvt-report.md (ticked by the scenario session) |
| T117 | live expiries in SCN-C, T089, SCN-B; join-wait part cut (L-SC-07) | scope-control.md (ticked by the scenario session) |

## Records and reviews
| Task | Record |
|---|---|
| T090 (with folded T112) | traceability/matrix.md, manifest CLEAN both ways; coverage of every FR, NFR, SC, SCN, US, PVT ID |
| T092 | t092-reviewer-walkthrough.md: fresh clone, endpoints for all eight runs, independent hash-chain reconstruction |
| T095 | README.md, quickstart.md, research.md (human documentation review is part of acceptance) |
| T096 | t096-checklist-convergence.md: 113 PASS, 2 ACCEPTED, 0 open |
| T098 | t098-history-review.md (human review of the record is part of acceptance) |
| T106 | DEP-02 wired; online scan never ran (no NVD key) - degraded path approved earlier; DEP-02 attestation signed |
| T107 | docs/attestations: PRV-02, LIC-02, DEP-02 ATTESTED at b28f515 |

## Note on the executor whitelist
The per-node whitelist has been enforced in code since 0bc2f00 (CHK047, human decision 2026-10-22). All eight
demonstration runs executed before that commit: they remain valid evidence of the behaviour at their own commits, but no
demonstration run has executed under the enforced whitelist - enforcement is evidenced by the test suite
(OperationWhitelistTest and every engine test running stages through it). A live run would only be needed for live
evidence of enforcement.

## Not part of this acceptance
T084 itself (this checkpoint), T099 (final release decision), T100 (G8 submission tag).
