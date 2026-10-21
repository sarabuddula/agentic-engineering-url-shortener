# Human Decisions 2026-10-21: Persistence Technology, T027-T034 and T113 Acceptance, T033 Choices, T106 Fallback

Date: 2026-10-21
Approver: Shilpa (human candidate)
Recorded words: "Option A. Accept T027-T034, T113. T033 choices confirmed. T106: fallback for now."
The options were prepared by the AI assistant (Claude Code), which does not approve.
Baseline commit: a985f1c

## Persistence technology (option A)
Context: the plan named JPA (entities and JPA repositories); ADR-003 requires only "H2 file mode, accessed only through
repository interfaces; uniqueness enforced by DB constraints". From T021 on, the assistant implemented persistence with
Spring JDBC (JdbcTemplate) without flagging the difference to the plan. Reported 2026-10-21 before T035.
Decision: amend the plan - persistence uses Spring JDBC behind repository interfaces; schema.sql stays authoritative;
the unused spring-boot-starter-data-jpa (Hibernate) is replaced by spring-boot-starter-jdbc. ADR-003 is unchanged.
Rejected: option B, converting to JPA entities (about 3-4 hours, no functional gain; the audit-chain row lock needs
native SQL either way).

## Task acceptance
Accepted: T027, T028, T029 (red suites), T030 (link domain), T031 (URL validation), T032, T033 (short-code
generation), T034 (idempotency), T113 (302/410 line in ADR-006).

## T033 implementation choices: confirmed
Generated code length 8; at most 3 regenerations after a collision (PVT-004 bound); exhaustion stores nothing.

## T106
Fallback for now: DEP-02 stays degraded (docs/compliance/dep-02-degradation.md) and is attested at T107 unless an NVD
API key is provided later. T106 remains open.
