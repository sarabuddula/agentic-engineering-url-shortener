# Agentic Software Engineering System — URL Shortener

A locally runnable prototype in two strictly separated planes, delivered as one Spring Boot fat jar (Java 21):

- **Application plane** — a URL shortener: create short links (validated, idempotent, expiring; default lifetime 24
  hours), redirect, link status lookup, redirect analytics, rate limiting, health.
- **Control plane** — a stateful orchestration engine that runs a software change through an explicit SDLC stage graph
  (N01 requirement intake to N14 engineering summary) with human approval gates, bounded retries and fallbacks,
  rollback versus compensation, safe-stop and governed resumption, dynamic replanning, a versioned policy evaluation
  (16 checks) that can block release, and a hash-chained audit trail from which every run can be reconstructed.

Specification, plan and tasks were produced with the SpecKit workflow and implemented gate by gate, failing test first.

**What the agents do at runtime, and what the human does.** The stage executors are deterministic, rule-based agents
with bounded autonomy (an enforced per-stage operation whitelist): they check requirement quality and stop on material
ambiguity, scan the codebase for the impact of a change, decompose work into tasks, assemble design, documentation,
evidence and the engineering summary, run the real test suites, and evaluate 16 policy checks that can block release.
They call no language model at runtime, so every step is reproducible from the audit trail. Design text and code are
written by the engineer with AI assistance (Claude Code) outside the runtime; a run accepts code only as a commit marked
with its run and task. Humans decide every gate: clarification, design, change, policy exceptions, accepted
limitations, release. In short: the system orchestrates, verifies and governs the work; it does not write the code.

## Start here

| What | Where |
|---|---|
| **Final engineering summary** (outcome, decisions, evidence, limitations) | [`docs/final-engineering-summary.md`](docs/final-engineering-summary.md) |
| **Architecture overview** (planes, stage graph diagram, testing approach, trade-offs) | [`docs/architecture.md`](docs/architecture.md) |
| **Reviewer guide** (25 checks: where, command, expected result) | [`docs/reviewer-guide.md`](docs/reviewer-guide.md) |
| Build, run, demonstration walkthrough, reviewer steps | [`specs/001-agentic-url-shortener/quickstart.md`](specs/001-agentic-url-shortener/quickstart.md) |
| Requirements (FR, NFR, SC, PVT, scenarios) and amendments | [`spec.md`](specs/001-agentic-url-shortener/spec.md) |
| Architecture, stage graph, failure semantics, policy set | [`plan.md`](specs/001-agentic-url-shortener/plan.md), [`data-model.md`](specs/001-agentic-url-shortener/data-model.md), [`docs/adr/adrs.md`](docs/adr/adrs.md) |
| API contract | [`contracts/openapi.yaml`](specs/001-agentic-url-shortener/contracts/openapi.yaml) |
| Tasks, execution order, amendment log | [`tasks.md`](specs/001-agentic-url-shortener/tasks.md) |
| Human decisions (gates, governed changes, checkpoints) | [`specs/001-agentic-url-shortener/approvals/`](specs/001-agentic-url-shortener/approvals/) |
| Scope control and disclosed limitations | [`docs/scope-control.md`](docs/scope-control.md) |
| Attestations (PRV-02, LIC-02, DEP-02) | [`docs/attestations/`](docs/attestations/) |
| Licenses and notices | [`docs/compliance/`](docs/compliance/) |
| Security policy and controls; how to contribute | [`SECURITY.md`](SECURITY.md), [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Traceability (manifest and matrix) | [`specs/001-agentic-url-shortener/traceability/`](specs/001-agentic-url-shortener/traceability/) |
| Demonstration evidence | [`docs/evidence/`](docs/evidence/) — see below |

## Quick start

**Needs:** Java 21, Maven 3.9+, git, and `gitleaks` 8.x on the PATH (the service runs Maven and gitleaks in its test and
policy stages), plus demo keys from `scripts/generate-demo-keys.sh` (without them the control plane answers 401). Start
the service from the repository root.

```bash
mvn clean verify                              # every suite: unit, contract, integration, security, reliability, e2e
eval "$(scripts/generate-demo-keys.sh)"       # demo keys: raw keys only in your shell, hashes in .demo-keys.yml
java -jar target/url-shortener-*.jar          # from the repository root; needs mvn and gitleaks on the PATH
```

Then follow the walkthrough in the quickstart.

## Demonstration evidence

| Record | What it shows |
|---|---|
| [`docs/evidence/runs/`](docs/evidence/runs/) | Exports (run, history, verify, release readiness) of the eight demonstration runs |
| [`scn-a-greenfield.md`](docs/evidence/scn-a-greenfield.md) | SCN-A greenfield: status lookup built red-first in a LIVE run (stopped at the completion gate, explained); completed as REPLAY |
| [`scn-c-replay.md`](docs/evidence/scn-c-replay.md) | SCN-C ambiguous requirement: G1 clarification, requirement version 2, resumption |
| [`scn-b-regression.md`](docs/evidence/scn-b-regression.md) | SCN-B brownfield change (A-006, 7 days to 24 hours): impact analysis vs actual diff, regression suite |
| [`t089-single-run.md`](docs/evidence/t089-single-run.md) | One run with kill-and-restart, approved replanning (distinct approver), parallel paths, READY, completed |
| [`t073-replan-acceptance.md`](docs/evidence/t073-replan-acceptance.md) | Accepted replan record |
| [`t093-accepted-limitations.md`](docs/evidence/t093-accepted-limitations.md) | Six limitations individually accepted at G6; READY WITH ACCEPTED LIMITATIONS |
| [`pvt-report.md`](docs/evidence/pvt-report.md) | Validation targets PVT-001..008 per demonstration run (demonstration-grade, C-004) |
| [`t092-reviewer-walkthrough.md`](docs/evidence/t092-reviewer-walkthrough.md) | Reviewer steps executed from a fresh clone; independent reconstruction |
| [`n09-test-outcomes.md`](docs/evidence/n09-test-outcomes.md) | Test cases each demonstration run executed and their results |
| [`scenario-evidence-review.md`](docs/evidence/scenario-evidence-review.md) | Three-scenario evidence review (guide section 21): readiness matrix, missing evidence |
| [`final-independent-assessment.md`](docs/evidence/final-independent-assessment.md) | Final independent assessment (guide section 23): 30 scored dimensions, verdict, concerns |
| [`red-first-audit.md`](docs/evidence/red-first-audit.md) | Failing test committed before implementation, task by task, with the deviations |

To reconstruct any run from the repository alone (the audit hash chain is recomputed from the export):

```bash
python3 scripts/reconstruct-run.py docs/evidence/runs/run-4738769c-4517-4597-b0b9-ee5796670220.json
```

## Honest limits

- Demonstration-grade measurements on a single local JVM, not production statistics (C-004).
- Deferred by governed scope decisions (see `docs/scope-control.md`): control-plane per-key and failed-authentication
  rate limits, staged revisions with HELD nodes and the replan cap, rework cycles, the join-wait bound with a gate on a
  parallel path (SCN-A.4, US-002 scenario 4).
- DEP-02 vulnerability scanning runs without an NVD key: degraded to a signed attestation based on an OSV.dev review;
  two spring-webmvc advisories remain, judged not applicable and disclosed.
- The per-stage operation whitelist is enforced since 0bc2f00 (2026-10-22); the eight demonstration runs predate it, so
  enforcement is evidenced by the test suite, not by a demonstration run.
- Spring Boot 3.5 is past its open-source support date (research.md); the upgrade path is Spring Boot 4 under change
  control.

## How this was built (AI-use disclosure)

The human candidate owns every decision in this repository: specification and plan approvals, ADR acceptance, every
gate decision in the demonstration runs (evidence class HUMAN), scope changes, attestations and release decisions —
each recorded under `approvals/`, `docs/attestations/` or in the runs' audit chains. Specification text, code, tests and
documentation were produced with AI assistance (Claude Code) under that human review and direction; the AI assistant
approved nothing (Constitution: "AI assistants MUST NOT approve their own decisions"). Approvals executed by test
fixtures are labelled SIMULATED and are never counted as human evidence.
