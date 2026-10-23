# Three-Scenario Evidence Review (execution guide section 21)

Read-only review, 2026-10-22, at c6dc9f9. Each check is answered from executed evidence, not from documentation: the
run exports in `docs/evidence/runs/` (history, /verify, release readiness), the TEST_OUTCOME rows in a read-only copy
of the demonstration database, and the fresh-clone re-run in `t092-reviewer-walkthrough.md`. Prepared by Claude Code
at the human candidate's direction; the candidate reviews it.

## Scenario readiness matrix

| Check | SCN-A greenfield | SCN-B brownfield | SCN-C ambiguous |
|---|---|---|---|
| Requirement input | fixture `scn-a.txt`, REQUIREMENT_INGESTED | fixture `scn-b.txt`, REQUIREMENT_INGESTED | "Add support for links.", REQUIREMENT_INGESTED |
| Interpretation | QUALITY_CHECKED: no clarification required (all four checks pass) | QUALITY_CHECKED: no clarification required; change-request branch selected | QUALITY_CHECKED: 2 material findings (AMB-1, AMB-2) |
| Decomposition | DECOMPOSITION_RECORDED, 5 tasks | DECOMPOSITION_RECORDED, 4 code tasks | DECOMPOSITION_RECORDED after clarification, 5 tasks |
| Dependency graph and path | N03 and N04 NOT_APPLICABLE with reason; N09/N10 joined at N11 | N04 IMPACT_ANALYSIS artifact before N05 | N03 taken; N04 not applicable |
| Human gates | G2, G5 (CHG-03 exception), G7, all HUMAN | CHANGE_APPROVAL, G2, G7, all HUMAN | G1 clarification, G2, G7, all HUMAN |
| Tests exist | LinkStatusTest, LinkStatusApiTest, red first (1137e75) | LinkCreationIT, ConfigurationBindingTest changed red first (9fc9da6) | ScnCCreateResolveTest; existing create and resolve suites |
| Validation executed | N09 ran 326 tests, all PASSED (run-934a50a1) | N09 ran 76 tests, all PASSED; full suite at e499405 exit 0 | N09 ran 328 tests, all PASSED (run-d33a19d6) |
| Failure behaviour | N08 safe-stop with no task commits; completion gate COMPLETION_BLOCKED | CHANGE_APPROVAL escalated once, answered in the re-issue window | LIVE run held at G1 before any implementation; G2 escalation, expiry, safe-stop and resume (run-05c9fd8d) |
| Audit evidence | 101 events, /verify verified; reconstructs | 103 events, /verify verified; reconstructs | 25, 47, 108 and 106 events, all verified; reconstruct |
| Documentation matches execution | yes (`scn-a-greenfield.md`) | yes (`scn-b-regression.md`), including the disclosed over-prediction | yes (`scn-c-replay.md`) |
| Terminal outcome | LIVE: SAFE_STOPPED at the completion gate. REPLAY: COMPLETED READY (run-4738769c) | LIVE: COMPLETED READY (run-8306f656) | LIVE: SAFE_STOPPED (defect; no-commit instruction). REPLAY: COMPLETED READY (run-d33a19d6) |
| Limitations disclosed | yes | yes | yes |

The three scenarios are materially different: SCN-A takes neither conditional branch (no clarification, no impact
analysis); SCN-B takes the N04 impact branch with a CHANGE_APPROVAL of an approved assumption (A-006); SCN-C takes the N03
clarification branch and changes the requirement to version 2 before decomposition.

## Missing evidence
1. **Per-run test outcomes were not in the repository.** The exports show N09 only as "exit criteria met"; the counts
   above come from the TEST_OUTCOME table of the demonstration database, which is not committed. Resolved by this
   review: `n09-test-outcomes.md` records them. It also shows that the SCN-B run's N09 selection did not include
   `LinkCreationIT`, one of the two changed tests; its pass is evidenced by the full suite (`scn-b-regression.md`).
2. **No LIVE greenfield or ambiguous run reached COMPLETED.** Both completed runs are REPLAY. SCN-B is the only LIVE
   completion.
3. **SCN-C has no replanning event.** The guide's ambiguous-scenario prompt (section 20, items 12-13) asks for a
   replanning event and invalidated, regenerated artifacts. Clarification happens at N03, before any downstream
   artifact exists, so nothing is invalidated; replanning is evidenced in run-4738769c (SCN-A variant) instead.

## Unsupported claims
None found. `scn-a-greenfield.md` cited history event #75 for "the real suite ran and passed", while #75 records only
"N09 exit criteria met". The claim is true (326 TEST_OUTCOME rows, all PASSED); the citation now points to
`n09-test-outcomes.md`.

## Cross-scenario inconsistencies
None. Gate roles, evidence classes, the policy version (POL-2026-10-18-v1) and the 16 policy outcomes are consistent
across all eight runs.

## Mandatory remediation
None blocking. The two documentation fixes this review found were made with it: `n09-test-outcomes.md` and the event
#75 citation in `scn-a-greenfield.md`.
