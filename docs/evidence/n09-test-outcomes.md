# Test outcomes recorded by the demonstration runs

The run exports in `runs/` show test execution only as node transitions ("N09 exit criteria met"). The outcomes are
TEST_OUTCOME rows, one per test case, which the runs wrote into the demonstration database (not committed). This file
records them so a reviewer can check them from the repository. Extracted 2026-10-22 from a read-only copy of that
database:

```sql
SELECT RUN_ID, COUNT(*), SUM(CASE WHEN STATUS='PASSED' THEN 1 ELSE 0 END), COUNT(DISTINCT SUITE), MIN(EXECUTED_AT)
FROM TEST_OUTCOME GROUP BY RUN_ID
```

| Run | Scenario | Class | Test cases | Passed | Not passed | Suites | First executed |
|---|---|---|---|---|---|---|---|
| run-05c9fd8d | SCN-C | REPLAY | 318 | 318 | 0 | 60 | 2026-10-22 13:03 -05 |
| run-934a50a1 | SCN-A | LIVE | 326 | 326 | 0 | 63 | 2026-10-22 15:04 -05 |
| run-8306f656 | SCN-B | LIVE | 76 | 76 | 0 | 17 | 2026-10-22 15:55 -05 |
| run-d33a19d6 | SCN-C | REPLAY | 328 | 328 | 0 | 63 | 2026-10-22 16:03 -05 |
| run-4738769c | SCN-A variant | REPLAY | 330 | 330 | 0 | 64 | 2026-10-22 16:31 -05 |
| run-85f0038b | SCN-A (G6) | REPLAY | 331 | 331 | 0 | 65 | 2026-10-22 18:27 -05 |

run-10190a85 and run-579bebe4 stopped before N09 and have no rows. Each run has two TEST_RUN operations (N09 and the
N12 evidence suite).

The suites are Surefire classes selected from the run's approved task list, so the counts differ by run. For SCN-B
(run-8306f656) the 17 suites include `ConfigurationBindingTest`, one of the two tests whose expectation changed, but
not `LinkCreationIT`, the other. Its pass under the change is evidenced by the full suite at e499405
(`scn-b-regression.md`: 295 unit and 117 integration tests, exit 0), not by the run itself.
