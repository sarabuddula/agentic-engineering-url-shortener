# Attestation DEP-02 - Dependency vulnerabilities (offline degradation)

Check (POL-2026-10-18-v1): dependency vulnerability scan clean (attestation when degraded).
Status: degraded - see docs/compliance/dep-02-degradation.md (no NVD API key, so OWASP DependencyCheck could not
update its data; no automated result exists).

## Checklist (answer each: YES / NO / NOT CHECKED, with a note)
1. You reviewed the runtime dependencies and versions in docs/compliance/licenses.xml: YES - the 52 compile/runtime
   scope dependencies from `mvn dependency:list -DincludeScope=runtime`, all present in licenses.xml.
2. You checked them against a vulnerability source you name here (for example GitHub Dependabot alerts on the private
   repository, OSV.dev, or a later scripts/dep-scan.sh run with NVD_API_KEY set): YES - OSV.dev (api.osv.dev
   querybatch, ecosystem Maven, exact versions), 2026-10-22.
3. Known vulnerabilities found (list ID, dependency, severity, whether it affects this use), or "none found": 17
   advisories on 5 runtime dependencies:
   - tomcat-embed-core 10.1.55 (fixed 10.1.58): GHSA-9xv2-5v5q-p794 / CVE-2026-65905 CRITICAL, DIGEST authenticator
     bypass - not used; GHSA-h3x4-894j-xpx5 / CVE-2026-68525 CRITICAL, FORM authentication - not used;
     GHSA-gcx9-497g-6cp6 / CVE-2026-65182 CRITICAL, improper access control - advisory too general to rule out.
   - spring-webmvc 6.2.19 (fixed 7.0.9 only): GHSA-j9f9-w8pj-32f8 / CVE-2026-47890 CRITICAL, SSE fragment rendering -
     not used; GHSA-pc63-qcmh-9cmg / CVE-2026-47884 CRITICAL, XsltView path limitation - not used.
   - jackson-core 2.21.4 (fixed 2.21.7): GHSA-7hhh-6rmp-j9qf / CVE-2026-89425 HIGH, unbounded growth on invalid
     tokens (DoS); GHSA-p6pp-m3f8-5c89 / CVE-2026-89407 HIGH, ReDoS in number parsing - likely applicable: four
     @RequestBody endpoints parse untrusted JSON.
   - jackson-databind 2.21.4 (fixed 2.21.7): HIGH GHSA-cxp5-3px4-pw24 (CVE-2026-91777), GHSA-q4xh-88c3-wmh7
     (CVE-2026-68497), GHSA-wv8q-qhhj-9h54 (CVE-2026-91776); MODERATE GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v,
     GHSA-gx83-3vf8-gh7j, GHSA-mhm7-754m-9p8w, GHSA-vvgp-rfg2-7rr6, GHSA-wjgm-6hv5-3cvf - mostly not applicable (no
     default typing, @JsonView, @JsonTypeInfo or @JsonUnwrapped in request types); the DoS items are uncertain.
   - log4j-api 2.24.3 (fixed 2.25.5): GHSA-qv9r-c865-cp47 / CVE-2026-49844 MODERATE, MapMessage JSON encoding - not
     used (logging goes through Logback).
4. If any apply: the compensating control or the policy exception (POST /runs/{runId}/policy-exceptions) you will use:
   none chosen yet. Remediation: upgrade Jackson to 2.21.7 and Tomcat to 10.1.58 (Spring Boot version properties),
   refresh licenses.xml, re-check; otherwise request a G5 policy exception with a compensating control.

## Evidence reviewed
- Source: OSV.dev API (querybatch and per-advisory details), checked 2026-10-22
- Applicability: searched src/main for SSE/XsltView, container DIGEST/FORM authentication, Jackson default typing and
  annotations, and @RequestBody endpoints
- Commit reviewed: 90e394a
- Prepared with AI assistance (Claude); reviewed, confirmed and decided by the human candidate.

## Decision
ATTESTED / NOT ATTESTED: NOT ATTESTED
Limitations: offline/degraded check, not an automated scan result. Known HIGH and CRITICAL advisories are open on
runtime dependencies; at least the jackson-core DoS advisories likely apply.

Attested by (key ID, never a raw key): demo-release-owner
Role: RELEASE_OWNER
Date: 2026-10-22
Reviewed commit: 90e394a
