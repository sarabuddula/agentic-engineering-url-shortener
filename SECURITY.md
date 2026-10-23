# Security

This repository is an assessment prototype, run locally. It is not deployed and holds no production data.

## Reporting a vulnerability

The repository is private. Report a suspected vulnerability privately to the repository owner (GitHub: sarabuddula),
not in an issue, with the affected file or endpoint, steps to reproduce and the commit. Do not include real secrets in
the report.

## Security controls in the prototype

| Area | Control | Evidence |
|---|---|---|
| Input validation | HTTP/HTTPS only; internal, local, private, IPv4-mapped and numeric address forms and userinfo URLs rejected; canonical form (D-003) | `UrlRejectionMatrixIT`, `UrlValidationTest` |
| Abuse | Per-IP creation and redirect rate limits with 429 and a retry hint; bounded client map | `RateLimitIT`, `ClientRateLimiterTest` |
| Authentication | Control-plane API keys, one role each (ENGINEER, APPROVER, RELEASE_OWNER, REVIEWER); only SHA-256 hashes are stored; key ID only in audit records | ADR-008, `KeyIdentityStoreTest` |
| Separation of duties | An approver's key must differ from the requester's; agents never approve; refusals are audited | `ApprovalLifecycleTest`, `ApprovalApiIT` |
| Agent autonomy | Per-stage operation whitelist, enforced; violations refused and audited | `OperationWhitelistTest` |
| Audit integrity | Per-run SHA-256 hash chain; tampering detected (not prevented) and located | `AuditChainTest`, `VerifyApiIT`, `scripts/reconstruct-run.py` |
| Secrets | No secrets or raw keys in code, logs or commits; gitleaks over the full history (allow-list `.gitleaks.toml`) | policy check SEC-03 |
| Dependencies | Inventory and pinning check (DEP-01), approved licenses (LIC-01), vulnerability review (DEP-02) | `docs/compliance/`, `docs/attestations/DEP-02.md` |

## Demo keys

`scripts/generate-demo-keys.sh` prints raw keys as shell `export` lines and writes only key IDs, roles and hashes to the
gitignored `.demo-keys.yml`. The keys are demonstration-only (A-009). Never commit raw keys or a `.env` file holding
them.

## Known security limitations

- Control-plane per-key and failed-authentication rate limits are configured but not enforced (L-SC-01, accepted).
- The link status lookup is not rate-limited (disclosed in the contract; adding a limit needs a G3 decision).
- DEP-02 has no automated NVD scan (no key); an OSV.dev review is attested, with two spring-webmvc advisories in
  features the application does not use.
- Spring Boot 3.5 is past its open-source support date; the upgrade path is Spring Boot 4 under change control.
- One person may hold several demo keys, so distinct key identity is a procedural control, not proof of distinct people.
- H2 file database without encryption at rest; a single local instance.
