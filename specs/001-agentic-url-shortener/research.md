# Research: Agentic Software Engineering System — URL Shortener

**Feature Branch**: `001-agentic-url-shortener` | **Date**: 2026-10-18 | **Status**: all decisions resolved (none pending)

| # | Decision | Rationale | Alternatives |
|---|---|---|---|
| 1 | Java 21 LTS | Candidate fluency under the 2–3 day timebox; typed workflow states and audit invariants (sealed types, records); virtual threads for PVT-003 concurrency | TypeScript/Node 22 (weaker runtime typing on state/audit invariants, lower fluency); Python 3.12 + FastAPI (concurrency and typing discipline weaker) |
| 2 | Spring Boot 3.x (Web MVC) | Actuator covers FR-010 health; security filter chain fits role-scoped keys and rate limiting; deepest candidate fluency | Quarkus 3.x (shallower fluency, custom auth path); Javalin/plain JDK (hand-built plumbing) |
| 3 | H2 file mode, behind repository interfaces (`com.h2database:h2` 2.3.232, runtime scope, Boot-managed, added at T008 on 2026-10-20; `mvn dependency:tree` line: `+- com.h2database:h2:jar:2.3.232:runtime`) | Zero-install durability for FR-022 resumability; MVCC for PVT-003; declarative uniqueness; PostgreSQL stays a documented swap path with a portable persistence test suite | SQLite (single-writer risk); PostgreSQL (external dependency vs A-004); in-memory-only (cannot satisfy FR-022) |
| 4 | JUnit 5 + Spring Boot Test + AssertJ + Mockito + REST Assured + ArchUnit (`spring-boot-starter-test` 3.5.16 added at T007 on 2026-10-20, test scope, Boot-managed: JUnit Jupiter 5.12.2, AssertJ 3.27.7, Mockito 5.17.0; see T007 dependency:tree output below; `archunit-junit5` pinned 1.5.1, test scope, at T009 on 2026-10-20, see T009 dependency:tree output below) | Spring-native; covers all seven NFR-009 categories; container-free domain tests for red-green-refactor; ArchUnit enforces plane/layer separation | Spock/Groovy (second toolchain, fluency risk); TestNG (fights Spring Boot's JUnit-5-first ecosystem) |
| 5 | Single Spring Boot fat jar, Maven single module | Simplest reviewer setup; kill-and-restart is the cleanest PVT-006 interruption/resumption demonstration | Two processes (inter-process contract, doubled ops surface); Docker Compose (mandatory Docker dependency vs A-004) |
| 6 | Workflow state as relational tables in the shared H2 store (ADR-005) | State transitions and audit events commit in one transaction; approvals carry explicit lifecycle columns; US-005 reconstruction is a query, not a rebuild | Event-sourced journal with replay/snapshots (machinery exceeds state size); per-run JSON files (no transactional coupling, no queryability) |
| 7 | Bucket4j `bucket4j_jdk17-core` (Apache-2.0; version to be verified and pinned at the engineering-baseline task; pinned 8.21.0, compile scope, at T003 on 2026-10-20, see T003 dependency:tree output below), own servlet filter | Tested token-bucket implementation for FR-047/A-008a-d; bounded bucket map with eviction rules and capacity-pressure test | Hand-rolled counter (concurrency bug risk under PVT-003); Resilience4j RateLimiter (broader toolbox than needed, larger scan surface) |
| 8 | 409 conflict response for same-identity/different-payload replay | Follows directly from FR-007 idempotency (request identity binds the request); prevents silent overwrite; approved by the human candidate 2026-10-18 as a C-005 spec delta | Return original with 200 (silent divergence between identity and payload); 422 (semantically wrong — the request is well-formed) |
| 9 | `swagger-request-validator-restassured` 2.44.8 (Apache-2.0) | Executable OpenAPI contract validation in API contract tests; version 2.44.8 verified by direct retrieval from Maven Central 2026-10-18; Apache-2.0 per the MVNrepository listing (the Maven Central page does not state the license) — approval condition met; pinned 2.44.8, test scope, at T003 on 2026-10-20, see T003 dependency:tree output below | Hand-written schema assertions (more code, drift risk) |
| 10 | DNS resolver mode switch: `live` (real DNS) or `fixed-local` (fixed local resolution table, no network) | `fixed-local` is the demonstration default: offline-safe, deterministic scenario runs (A-004); `live` available when real resolution is wanted; tests inject the stub resolver (PVT-003) | Always-live DNS (demos depend on network); no address resolution (fails D-003 rule set) |
| 11 | DNS access behind an explicit resolver interface | Testability (stub injection) and a declared trust boundary (Constitution VII) | Direct `InetAddress` calls (untestable, hidden dependency) |
| 12 | Simple bounded thread pool for parallel stage executors | Minimal sufficient parallelism to satisfy FR-043 (parallel paths + synchronization) without framework machinery; disclosed as a minimal implementation | Actor framework (complexity unjustified under timebox); purely sequential execution (fails FR-043) |
| 13 | Surefire (`*Test`) / Failsafe (`*IT`) split | Every test category executes under `mvn verify`; ArchUnit runs in the unit phase | Single plugin for all categories (slower feedback, blurred category boundaries) |

## Toolchain acceptance (2026-10-20)

Decision: I, the human candidate, accept JDK 21.0.12.1 (Temurin) and Apache Maven 3.9.16 as the toolchain for T001.

Observed output, run 2026-10-20 on the candidate's machine:
- `java -version`: openjdk version "21.0.12.1" 2026-08-18 LTS; OpenJDK Runtime Environment Temurin-21.0.12.1+1 (build 21.0.12.1+1-LTS)
- `mvn -v`: Apache Maven 3.9.16 (2bdd9fddda4b155ebf8000e807eb73fd829a51d5); Java version: 21.0.12.1, vendor: Eclipse Adoptium; OS: mac os x 13.7.8, x86_64

## Spring Boot pin and disclosed limitation (2026-10-20; governed edit reviewed by the human candidate)

Decision: the human candidate pinned Spring Boot 3.5.16 (`spring-boot-starter-parent`) at T001, within ADR-002 (Spring Boot 3.x). Observed on Maven Central 2026-10-20: 3.5.16 is the latest 3.x release; the latest overall is 4.2.0-M2.

Disclosed limitation: open-source support for the Spring Boot 3.5 line ended on 30 June 2026 (date confirmed by the human candidate 2026-10-20). The prototype therefore runs on a framework line without upstream OSS patches; this is relevant to DEP-02 (vulnerability scan) and release readiness.

Upgrade path: Spring Boot 4.x. A move to 4.x is a change to the approved architecture (ADR-002 names 3.x) and requires impact analysis and change approval under C-005; it is not part of the current task plan.

## T003 dependency:tree output (2026-10-20)

`mvn dependency:tree` run 2026-10-20 on the candidate's machine (JDK 21.0.12.1, Maven 3.9.16), exit 0, BUILD SUCCESS. Lines for the project and the two pinned dependencies, as printed:

```text
com.agenticengineering:url-shortener:jar:0.1.0-SNAPSHOT
+- com.bucket4j:bucket4j_jdk17-core:jar:8.21.0:compile
\- com.atlassian.oai:swagger-request-validator-restassured:jar:2.44.8:test
```

The validator brings in `swagger-request-validator-core` 2.44.8 and `io.rest-assured:rest-assured` 5.5.7 (both test scope). Observed on Maven Central the same day: newer releases exist (Bucket4j none newer than 8.21.0; validator 2.44.9 and 3.0.0). The approved versions were kept; any version change needs human approval.

## T004 build-time tool pin (2026-10-20)

`org.codehaus.mojo:license-maven-plugin` pinned 2.7.1 in `pom.xml` at T004 (LIC-01; not managed by Spring Boot). 2.7.1 was the latest release on Maven Central on that date. `mvn license:download-licenses` exited 0 and wrote `docs/compliance/licenses.xml` (125 dependencies, each with a license entry); 12 warnings were failed downloads of license texts from gnu.org, with license names still recorded. The manifest reflects the pom as of T004 and must be regenerated before any license check relies on it.

Licensing items for the policy checks (T057, T107), recorded without a decision: `org.hibernate.orm:hibernate-core` (GNU Library General Public License v2.1 or later; removed from the build 2026-10-21 with the JPA starter); `com.sun.mail:mailapi` (CDDL/GPLv2+CE); `ch.qos.logback` logback-classic and logback-core (EPL-2.0 or LGPL-2.1-only); eight `com.github.java-json-tools` artifacts (Apache-2.0 or LGPL-3.0+).

## T005 secret scanner (2026-10-20)

gitleaks 8.30.1 (MIT), installed with Homebrew on 2026-10-20 at the human candidate's request; `gitleaks version` printed `8.30.1`. Not a Maven dependency. Config: `.gitleaks.toml` (default rules plus one documented exclusion for the `key_hash: <64 hex>` lines of the gitignored `.demo-keys.yml`). Scans run 2026-10-20: `gitleaks git --config .gitleaks.toml --redact` exit 0 and `gitleaks dir . --config .gitleaks.toml --redact` exit 0, both "no leaks found". Without the exclusion, the working-directory scan reported 4 generic-api-key findings, all `key_hash` lines in `.demo-keys.yml`. A check with a temporary copy of that file showed a raw-key line in it is still reported.

## T007 dependency:tree output (2026-10-20)

`mvn dependency:tree` run 2026-10-20 (JDK 21.0.12.1, Maven 3.9.16), exit 0. Lines for the test starter and its main test libraries, as printed:

```text
+- org.springframework.boot:spring-boot-starter-test:jar:3.5.16:test
|  +- org.assertj:assertj-core:jar:3.27.7:test
|  +- org.junit.jupiter:junit-jupiter:jar:5.12.2:test
|  +- org.mockito:mockito-core:jar:5.17.0:test
|  +- org.mockito:mockito-junit-jupiter:jar:5.17.0:test
```

## T009 dependency:tree output (2026-10-20)

`mvn dependency:tree` run 2026-10-20 (JDK 21.0.12.1, Maven 3.9.16), exit 0. ArchUnit lines, as printed. 1.5.1 was the latest release on Maven Central on that date.

```text
+- com.tngtech.archunit:archunit-junit5:jar:1.5.1:test
|  +- com.tngtech.archunit:archunit-junit5-api:jar:1.5.1:test
|  |  \- com.tngtech.archunit:archunit:jar:1.5.1:test
|  \- com.tngtech.archunit:archunit-junit5-engine:jar:1.5.1:test
|     \- com.tngtech.archunit:archunit-junit5-engine-api:jar:1.5.1:test
```

## T033 short-code implementation choices (2026-10-21, within ADR-006)

ADR-006 fixes the base-62 alphabet, the minimum length of 4, random generation, uniqueness by the database constraint and a bounded regeneration on collision, without numbers. Implementation choices, to be confirmed at the T050 checkpoint: generated length 8 (the length of the contract example `aB3xK9mQ`; 62^8 is about 2.2e14 codes); at most 3 regenerations after the first attempt, reusing the approved retry bound of PVT-004; exhaustion raises ShortCodeCollisionException and stores nothing (mapped to the generic 500 INTERNAL_ERROR); reserved route words `api` and `actuator` compared without case; java.security.SecureRandom as the random source.

## DEP-02 remediation overrides (2026-10-22, human decision recorded in the DEP-02 attestation)

The DEP-02 review at 90e394a (OSV.dev, no NVD key) found 17 advisories on 5 runtime dependencies. `pom.xml` overrides
three Spring Boot 3.5.16 managed versions (commit 6bb24f0): `jackson-bom.version` 2.21.4 -> 2.21.8, `tomcat.version`
10.1.55 -> 10.1.61 (the advised 10.1.58 was never published to Maven Central) and `log4j2.version` 2.24.3 -> 2.25.5, the
latest patch of each line on that date; `docs/compliance/licenses.xml` regenerated (version changes only). Re-check: 15
of 17 advisories cleared; the remaining two on spring-webmvc 6.2.19 (GHSA-j9f9-w8pj-32f8, GHSA-pc63-qcmh-9cmg) are fixed
only in Spring Framework 7 and concern SSE fragment rendering and XsltView, which the application does not use -
disclosed in `docs/attestations/DEP-02.md` (re-review at b28f515).

