# Attestation DEP-02 - Dependency vulnerabilities (offline degradation)

Check (POL-2026-10-18-v1): dependency vulnerability scan clean (attestation when degraded).
Status: degraded - see docs/compliance/dep-02-degradation.md (no NVD API key, so OWASP DependencyCheck could not
update its data; no automated result exists).

## Checklist (answer each: YES / NO / NOT CHECKED, with a note)
1. You reviewed the runtime dependencies and versions in docs/compliance/licenses.xml: ____
2. You checked them against a vulnerability source you name here (for example GitHub Dependabot alerts on the private
   repository, OSV.dev, or a later scripts/dep-scan.sh run with NVD_API_KEY set): ____
3. Known vulnerabilities found (list ID, dependency, severity, whether it affects this use), or "none found": ____
4. If any apply: the compensating control or the policy exception (POST /runs/{runId}/policy-exceptions) you will use: ____

## Evidence reviewed
(source, date checked, commit SHA)

## Decision
ATTESTED / NOT ATTESTED: ____
Limitations: offline/degraded check, not an automated scan result.

Attested by (key ID, never a raw key): ____
Role: RELEASE_OWNER
Date: ____
Reviewed commit: ____
