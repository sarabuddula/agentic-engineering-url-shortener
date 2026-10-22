# SCN-B regression evidence (T070) - default link TTL 7 days -> 24 hours

Run `run-8306f656-0866-4e87-b538-e5977c7c4431` (LIVE, T069). Governed change of A-006 approved at CHANGE_APPROVAL
(approval 10) and G2 (approval 11) by demo-approver (HUMAN), 2026-10-22.

## Change
`app.default-ttl-days` 7 -> 1 (= 24 hours), commit e499405. No Java change: `ShortenerConfiguration` passes
`Duration.ofDays(defaultTtlDays)` to `LinkService`. Links created before the change keep their stored `EXPIRES_AT`.

## Regression suite
`mvn clean verify` at e499405: exit 0 - 295 unit tests (Surefire) and 117 integration tests (Failsafe), 0 failures,
0 errors, 0 skipped. No failure had to be fixed: there was no regression defect.

## Test expectations changed
`git diff 9fc9da6^ e499405 -- src/test` changes exactly two assertions, both asserting the superseded default and both
listed in the impact analysis (IMPACT_ANALYSIS v1, sha256 162bea9d..., regression risk 4):

| Test | Before | After |
|---|---|---|
| `LinkCreationIT.createsALinkWithTheDefaultTtl` | `expires_at = created_at + 7 days` | `expires_at = created_at + 24 hours` |
| `ConfigurationBindingTest` | `defaultTtlDays() == 7` | `defaultTtlDays() == 1` |

Both were changed first and failed against the 7-day configuration (commit 9fc9da6), then passed after the change.

Not changed (they pass the TTL as a parameter or use it as a fixture timestamp, not as the application default):
LinkDomainTest, IdempotencyTest, ShortCodeGeneratorTest, LinkStatusTest, RedirectIT, RedirectAnalyticsIT,
AnalyticsSummaryIT, AppPlaneContractIT, LinkStatusApiTest, PerformanceIT, RedirectPersistenceFailureIT.

## Impact analysis vs actual diff
Predicted components: ShortenerConfiguration, ShortenerProperties, LinkService, configuration `app.default-ttl-days`.
Actual code diff: `src/main/resources/application.yml` only (the configuration value the components read). Predicted
documentation updated in t4: contract description, spec A-006, quickstart.
