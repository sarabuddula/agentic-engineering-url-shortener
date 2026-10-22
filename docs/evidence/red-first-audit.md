# Red-First Audit (T114)

Generated 2026-10-22 from `git log` on main (Constitution IV, C-003). For every implementation commit (`feat(...)` or
`fix(...)`) the first failing-test commit (`test(...)`) of the same task, or of the paired "write failing tests" task
named in its Prereq field (for example T067 for T068), must come earlier in history. The expected failure reason is
recorded in each red commit's message.

| Task | Red commit (task) | First implementation commit | Result |
|---|---|---|---|
| T006 | - | c1d2580 | DEVIATION (see below) |
| T007 | - | c9b4958 | DEVIATION (see below) |
| T008 | - | c9b4958 | DEVIATION (see below) |
| T010 | ff87fbf (own) | a8871e5 | red first |
| T016 | 341c2f4 (T015) | c7f52ff | red first |
| T017 | 827436b (own) | bce0769 | red first |
| T018 | 9779835 (own) | 5e61a2a | red first |
| T019 | f802809 (own) | 36eb431 | red first |
| T021 | 3c46b67 (T020) | daeb127 | red first |
| T022 | 66d523d (own) | 3c6b4b5 | red first |
| T023 | 0c902db (own) | 57fe574 | red first |
| T026 | e7183cf (own) | e7913b2 | red first |
| T030 | b6b816c (T027) | 8e1ad51 | red first |
| T031 | bba1052 (T029) | 65ffa03 | red first |
| T033 | 88cc464 (T032) | a985f1c | red first |
| T034 | 969d8d8 (T028) | 54a4302 | red first |
| T035 | 09ecf7e (own) | 2e55eb2 | red first |
| T036 | 5a10657 (own) | 4cfe283 | red first |
| T037 | 4716ecf (own) | af801e4 | red first |
| T039 | c3bb6dc (own) | 8f8dc5a | red first |
| T040 | c32b4de (own) | b669776 | red first |
| T041 | 6e89188 (own) | 0668618 | red first |
| T048 | 3c0b1db (own) | 6877a4d | red first |
| T052 | 29ff459 (own) | 5d529b0 | red first |
| T053 | fe5061b (T051) | a7e3c72 | red first |
| T054 | e9360d7 (own) | 63b1be0 | red first |
| T056 | bc2e9b9 (T055) | 124ca9b | red first |
| T057 | 002a6ae (own) | 81682a9 | red first |
| T058 | 840fcac (own) | 346715e | red first |
| T060 | a31acd3 (T059) | c44edf6 | red first |
| T061 | d90c589 (own) | 50b2992 | red first |
| T062 | 82322fd (own) | d6edf42 | red first |
| T063 | 42340f3 (own) | c82944d | red first |
| T064 | 0969bd0 (own) | c91afc2 | red first |
| T066 | 8521e27 (own) | b5add96 | red first |
| T068 | 3236bba (T067) | eb612fe | red first |
| T072 | c9b2ea2 (T071) | bef1c41 | red first |
| T075 | 62ec56d (T074) | 4a8f3b5 | red first |
| T076 | e426d21 (own) | a64bd65 | red first |
| T077 | 8f9eedd (own) | e0adc6c | red first |
| T078 | d831e75 (own) | dd1a494 | red first |
| T079 | 77d0dcb (own) | 0f145a3 | red first |
| T080 | a70b7d5 (own) | a5ee73c | red first |
| T081 | a70b7d5 (own) | a5ee73c | red first |
| T082 | 4e92d74 (own) | ae3d50f | red first |
| T083 | c468734 (own) | 54f81d0 | red first |
| T085 | 653efc4 (own) | 6aa7e89 | red first |
| T086 | 79fbbbc (own) | 746eef5 | red first |
| T087 | 083f057 (own) | e1ff377 | red first |
| T091 | c6f01bb (own) | 3fa580a | red first |
| T101 | 5f9b2fb (own) | 960799d | red first |
| T102 | b57acd8 (own) | 42035d1 | red first |
| T104 | c468734 (own) | 54f81d0 | red first |
| T105 | 2e4d686 (own) | 958ca17 | red first |
| T108 | c32b4de (own) | b669776 | red first |
| T110 | 31e82b2 (own) | a965aeb | red first |
| T118 | 05700ed (own) | 2b5e7e6 | red first |
| T119 | 231c78d (own) | 7e275a7 | red first |
| T122 | ae08d7c (own) | fb6a094 | red first |
| T123 | c4ff22a (own) | 7a64e09 | red first |
| T127 | f37c01a (own) | 80aebfc | red first |
| T128 | eb23ee5 (own) | 1b4eb44 | red first |

## Deviations and notes
- T006 (demo-key generator): a shell script; validated manually as its task's Validate field specifies
  (`eval "$(scripts/generate-demo-keys.sh)"`), no automated red test. The generated keys are exercised later by the
  T012 fixtures.
- T007, T008 (resolver seam, externalized configuration; 2026-10-20): FixedLocalResolverTest and
  ConfigurationBindingTest were committed together with the implementation in c9b4958, so the failing state was never
  committed. Not red-first; recorded here rather than rewritten.
- T053: its "part 1" commit (a7e3c72) turned the earlier T051 red suite green; T053's own red suite (4d246d1) precedes
  "part 2" (3471494).
- Tests added after the fact and labelled "not red-first" in their commit messages: T109 (mutation-checked),
  BlockingReasonsFitTest (T080/T081), PersistedEvidenceProviderTest (regression guard, T082), one ReleaseReadinessTest
  case already passing on T062 behaviour.
- Test changes after a red commit are disclosed in the implementation commit that made them (T068, T072, T075, T101,
  T118, T119, T122, T123, T128 and the POST /runs start fix).
