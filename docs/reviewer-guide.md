# Reviewer Guide

Where to find and how to check each item a reviewer is likely to look for. Commands run from the repository root.
Commands marked *service* need the running service and demo keys (README, Quick start); the others need only the
repository and Maven. Run exports in `docs/evidence/runs/` are the eight demonstration runs. A run is **LIVE** when the
work was done during the run and **REPLAY** when it re-executes work done earlier; every document states which.

| # | Item | Where | Command | Expected result | IDs |
|---|---|---|---|---|---|
| 1 | Project objective | `README.md`, `docs/final-engineering-summary.md` section 2 | - | Two planes: URL shortener and SDLC orchestration control plane | FR-020 |
| 2 | Run the application | `specs/001-agentic-url-shortener/quickstart.md` "Run" | `java -jar target/url-shortener-*.jar` | `GET /actuator/health` returns `UP` | FR-010 |
| 3 | Run the tests | quickstart "Build and test" | `mvn clean verify` | exit 0; 302 unit and 122 integration tests, 0 failures | Constitution IV |
| 4 | Exercise the URL shortener | quickstart walkthrough step 1 | `curl -X POST localhost:8080/api/v1/links ...` then `curl -i localhost:8080/<code>` | 201 then 302 to the target; replay with the same Idempotency-Key gives 200 | FR-001, FR-005, FR-007 |
| 5 | Start an orchestration run | quickstart walkthrough step 2 | *service* `POST /api/v1/orchestrator/runs` (engineer key) | 201 with a run ID; the run proceeds to its first gate | FR-020 |
| 6 | Inspect workflow state | quickstart walkthrough step 4 | *service* `GET /runs/<id>`, `GET /runs/<id>/history` | node statuses, pending approvals, the full ordered lineage | FR-022, FR-023 |
| 7 | Perform a human approval | quickstart walkthrough step 3 | *service* `POST /runs/<id>/approvals/<approvalId>` (approver key) | 200; with the requester's own key 403 (self-approval refused, audited) | FR-024, FR-046 |
| 8 | Retry | `docs/evidence/pvt-report.md` (PVT-004) | `mvn test -Dtest='ReliabilityTest,ReliabilityEngineTest'` | pass: FAILED -> RETRY_WAITING -> RUNNING, at most 3 retries, then fallback or safe-stop. No demonstration run hit a transient failure, so retry is evidenced by tests only | FR-025, FR-026 |
| 9 | Rollback and compensation | plan section 6 | `mvn test -Dtest='RollbackTest,CompensationTest'` | pass: uncommitted rows roll back; committed code is compensated by a revert commit; rollback of committed code refused and audited. Tests only, no demonstration run | FR-027, FR-049 |
| 10 | Safe-stop | `docs/evidence/scn-a-greenfield.md`, `docs/evidence/t089-single-run.md` | `python3 scripts/reconstruct-run.py docs/evidence/runs/run-4738769c-4517-4597-b0b9-ee5796670220.json` | BOOT_RECOVERY safe-stop after a JVM halt, then an APPROVER resume; run-934a50a1 safe-stopped at the completion gate | FR-028 |
| 11 | Dynamic replanning | `docs/evidence/t073-replan-acceptance.md` | same reconstruction command as row 10 | REPLAN_REQUESTED, REPLAN_ACCEPTANCE approved by an approver other than the requester, REVISION_1 activated | FR-029 |
| 12 | Greenfield scenario | `docs/evidence/scn-a-greenfield.md` | reconstruct run-934a50a1 (LIVE) or run-4738769c (REPLAY) | no clarification raised; decomposition, design, red-first code, tests, policy, release decision. The LIVE run stopped at the completion gate; the completed run is REPLAY | FR-040, SCN-A |
| 13 | Brownfield scenario | `docs/evidence/scn-b-regression.md` | reconstruct run-8306f656 | impact analysis before the change, CHANGE_APPROVAL and G2, two predicted test changes, COMPLETED READY (LIVE) | FR-041, SCN-B |
| 14 | Ambiguous-requirement scenario | `docs/evidence/scn-c-replay.md` | reconstruct run-579bebe4 (LIVE) or run-d33a19d6 (REPLAY) | ambiguity found at N02, run held at G1, answer becomes requirement v2, resumes at the correct state | FR-035, FR-042, SCN-C |
| 15 | Architecture | `docs/architecture.md` | - | diagrams of both planes and the N01-N14 graph; how a run executes | Constitution II |
| 16 | ADRs | `docs/adr/adrs.md` | - | ADR-001..009 accepted by the candidate, each with an evidence line | Constitution VII |
| 17 | Requirement traceability | `specs/001-agentic-url-shortener/traceability/` | `scripts/trace-manifest.sh check` | `traceability: CLEAN`, exit 0 | FR-030, CHG-03 |
| 18 | Audit evidence | `docs/evidence/runs/` | `python3 scripts/reconstruct-run.py docs/evidence/runs/<run>.json`; *service* `GET /runs/<id>/verify` | recomputed hash chain matches the exported head (`match=True`) | FR-030, FR-048 |
| 19 | Reliability measurements | `docs/evidence/pvt-report.md`, `docs/evidence/pvt/metrics-demo.json` | *service* `GET /api/v1/metrics/demo` | success rate, retry and compensation counts, MTTR, latency, labelled DEMONSTRATION | FR-031 |
| 20 | Security controls | `SECURITY.md`, plan section 8 | `mvn verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='UrlRejectionMatrixIT,RateLimitIT'` | pass: disallowed and internal targets rejected; 429 with a retry hint | FR-002, FR-003, FR-047 |
| 21 | Known limitations | `docs/scope-control.md`, `docs/evidence/t093-accepted-limitations.md` | - | six limitations, each accepted individually at G6 | FR-037 |
| 22 | Final engineering summary | `docs/final-engineering-summary.md` | - | the 21 sections of the guide's schema | FR-038 |
| 23 | API contracts and schemas | `specs/001-agentic-url-shortener/contracts/openapi.yaml`, `src/main/resources/schema.sql`, plan section 2 (versioning rules) | `mvn test -Dtest=OpenApiContractHarnessTest` | pass; every contract IT validates responses against the contract; `/api/v1` changes are additive only | FR-020, plan section 2 |
| 24 | Compliance and change-control results | `docs/evidence/runs/` (`release_readiness`), `docs/attestations/` | *service* `GET /runs/<id>/release-readiness` | 16 policy outcomes per run (`release_readiness.policy_outcomes` in each export); run-934a50a1 shows CHG-03 EXCEPTION_REQUESTED, approved at G5 (approval 8, `scn-a-greenfield.md`) | FR-032, FR-033, FR-039 |
| 25 | MTTR inputs | `docs/evidence/pvt-report.md` (failure events per run), `docs/evidence/pvt/metrics-demo.json` | `mvn test -Dtest=MttrTest` | pass; MTTR over recovered events only, governance wait reported separately, unrecovered events counted separately | FR-031 |
