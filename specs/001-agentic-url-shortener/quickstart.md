# Quickstart: Agentic Software Engineering System — URL Shortener

**Feature Branch**: `001-agentic-url-shortener` | **Date**: 2026-10-18 | **Status**: Reviewed at Human Gate 4 (2026-10-18); maintained during implementation (jar name and configuration table updated 2026-10-20)

## Prerequisites

- Java 21 (LTS), Maven 3.9+, git
- Network access is needed twice, both one-time: (1) the first `mvn` build downloads dependencies from Maven Central; (2) the first DEP-02 vulnerability scan caches the NVD feed (OWASP DependencyCheck). After that, builds, tests, and the demonstration run fully offline — the service uses the fixed-local resolver at runtime (see below).

## Setup

```bash
git clone <repo-url> agentic-engineering-url-shortener
cd agentic-engineering-url-shortener
```

Generate demo keys with the helper script (A-009/D-004). The committed `.demo-keys.yml` template is replaced by a gitignored file holding only key IDs, roles, and SHA-256 hashes; the script prints raw keys as `export` lines for your shell and nothing else. Raw keys exist only in your shell environment — never in the file, in logs, or in commits (NFR-001).

```bash
eval "$(scripts/generate-demo-keys.sh)"   # exports ENGINEER_KEY, APPROVER_KEY, RELEASE_OWNER_KEY, REVIEWER_KEY
```

The script generates a second, distinct approver identity — self-approval is prohibited (FR-046). Approval roles by gate (ADR-008, accepted; plan §5 numbering): G1 clarification, G2 architecture, G3 security-sensitive, G4 destructive actions, and replan acceptance use the APPROVER key; G5 policy exceptions, G6 accepted limitations, G7 release readiness, and G8 final submission use the RELEASE_OWNER key; the REVIEWER key is read-and-verify only; every approver's key identity must differ from the requester's.

## Fixed-local resolver

Under `fixed-local` mode (the demonstration default), hostname resolution comes from a fixed table and the service performs no DNS queries. The table is committed at `config/dns/fixed-local-resolver.yml`:

```yaml
resolver-mode: fixed-local
entries:
  example.com: 93.184.216.34
  example.org: 93.184.215.14
```

Behavior: a target hostname present in the table resolves to the listed address and proceeds through the D-003 checks (internal/local/private/unusual-form rejections still apply to the resolved address); a hostname absent from the table fails resolution and the create call is rejected with a resolution failure, which is auditable and counted in analytics. Add hostnames used by demonstration fixtures to this table.

## Configuration (defaults in `application.yml`; overrides in `application-local.yml`)

| Property | Default | Notes |
|---|---|---|
| `app.dns.resolver-mode` | `fixed-local` | `fixed-local`: fixed local resolution table (`config/dns/fixed-local-resolver.yml`), no network — demonstration default, offline-safe and deterministic. `live`: real DNS. Tests inject a stub resolver (PVT-003). |
| `app.rate-limit.creation-per-ip` | 60/min, burst 100 | A-008a; configurable low limits for rate-limit tests |
| `app.rate-limit.redirect-per-ip` | 600/min, burst 200 | A-008b |
| `app.data-dir` | `./data` | H2 file database location (T026, ADR-003); state survives restarts |
| `app.rate-limit.control-plane-per-key` | 120/min | A-008c - deferred 2026-10-21 (L-SC-01): configured but not enforced |
| `app.rate-limit.failed-auth-per-ip` | 10/min, then 5-min block | A-008d + D-004 - deferred 2026-10-21 (L-SC-01): configured but not enforced |
| `app.default-ttl-days` | 1 (24 hours) | A-006, amended 2026-10-22 (SCN-B; was 7) |
| `app.approval-timeout-minutes` | 5 | PVT-005 (escalation re-issue, equal second window) |
| `app.dns.lookup-timeout` | 3s | A-015; bounds `live` DNS lookups |
| `app.rework-cap` | 2 | A-016; rework cycles per rejected gate before governed termination |
| `app.replan-cap` | 2 | A-017; accepted replans per run |
| `app.sync-wait-approval-windows` | 2 | A-018; approval windows a synchronization point waits for a stopped path |

## Build and test

```bash
mvn clean verify
```

`mvn verify` executes every test category (Surefire `*Test`, Failsafe `*IT`): domain unit, API contract (validated against `specs/001-agentic-url-shortener/contracts/openapi.yaml`), persistence, integration, orchestration transition, approval/rejection, retry/timeout, fallback, rollback/compensation, safe-stop/resumption, replanning, concurrency (PVT-003), performance (PVT-001/002), security, e2e scenarios, and release-readiness checks. ArchUnit layer rules fail the build on forbidden dependencies.

Offline note: DEP-02 uses the NVD data cached during the first online run; with no network and no cache, the check degrades to attestation and the degradation is recorded (plan §8).

## Run

```bash
mvn spring-boot:run
# or: java -jar target/url-shortener-*.jar
```

- Application plane: `http://localhost:8080/api/v1/links`, `http://localhost:8080/{code}`
- Control plane (role-scoped `X-API-Key`): `http://localhost:8080/api/v1/orchestrator/runs`
- Health: `http://localhost:8080/actuator/health`

## Demonstration walkthrough

```bash
# 1. Create a link and capture the returned short code (201 = new link; 200 = idempotent replay)
SHORT_CODE=$(curl -s -X POST localhost:8080/api/v1/links \
  -H 'Idempotency-Key: demo-key-001' -H 'Content-Type: application/json' \
  -d '{"target_url":"https://example.com/long/path"}' | jq -r .short_code)
curl -i localhost:8080/$SHORT_CODE              # 302 to the target
curl -s localhost:8080/api/v1/links/$SHORT_CODE/status   # {"short_code":…,"status":"ACTIVE","expires_at":…}
# Status lookup (SCN-A): ACTIVE | EXPIRED | UNKNOWN; read-only (no redirect, no redirect event, not rate-limited);
# an unknown code answers 200 with "status":"UNKNOWN" and no expires_at

# 2. Start a workflow run (engineer key)
curl -X POST localhost:8080/api/v1/orchestrator/runs -H "X-API-Key: $ENGINEER_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"requirement_text":"Analytics summary endpoint returning redirect counts by outcome for a link"}'

# 3. Find the pending approval in the run detail, then decide (approver key — a DIFFERENT identity; self-approval returns 403, FR-046)
curl -s localhost:8080/api/v1/orchestrator/runs/<runId> -H "X-API-Key: $APPROVER_KEY" | jq .pending_approvals
curl -X POST localhost:8080/api/v1/orchestrator/runs/<runId>/approvals/<approvalId> \
  -H "X-API-Key: $APPROVER_KEY" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","reason":"ADRs reviewed"}'

# 4. Inspect lineage and verify the audit chain (reviewer key)
curl localhost:8080/api/v1/orchestrator/runs/<runId>/history -H "X-API-Key: $REVIEWER_KEY"
curl localhost:8080/api/v1/orchestrator/runs/<runId>/verify -H "X-API-Key: $REVIEWER_KEY"
```

### Interruption and resumption (PVT-006)

```bash
# While a run is executing:
kill -9 $(pgrep -f urlshortener)      # hard interruption
java -jar target/url-shortener-*.jar   # restart
curl -X POST localhost:8080/api/v1/orchestrator/runs/<runId>/resume -H "X-API-Key: $ENGINEER_KEY"
```

The run resumes from durable state; operation outcomes without completion records are reconciled (or the run safe-stops) — no duplicate side effects, including the after-effect/before-completion-record case.

## Reviewer steps (US-005, SC-006)

1. `git clone` the repository; run `mvn clean verify` — all suites must pass.
2. Start the service and replay the demonstration walkthrough above (reviewer key).
3. For each demonstration run: read `/history`, run `/verify` (chain integrity), read `/release-readiness` (policy outcomes + decision), read `/api/v1/metrics/demo` (labeled DEMONSTRATION).
4. Reconstruct the SCN-A-variant run end-to-end from repository artifacts and persisted records only — states, decisions, approvals, retries, failures, replanning, terminal outcome.
5. The traceability matrix is generated from the mapping manifest joined with the persisted tables; both directions (requirement → evidence, evidence → requirement) must be clean (CHG-03).
