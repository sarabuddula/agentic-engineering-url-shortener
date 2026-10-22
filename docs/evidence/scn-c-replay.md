# SCN-C demonstration (REPLAY) - clarified requirement mapped to existing code

Run `run-05c9fd8d-0888-4b45-a1ae-4516f00688c4`, execution class **REPLAY** (T088, plan section 11).

## Requirement and clarification
- Version 1 (fixture): "Add support for links." - N02 found two material findings, AMB-1 'support' and AMB-2 'links'.
- G1 clarification, approval 4 by demo-approver (HUMAN): "links = the existing URL-shortener short links;
  support = create (POST /api/v1/links) and resolve (GET /{code} redirect)" - recorded as requirement version 2,
  provenance CONFIRMED.
- G2 design approval: approval 5 expired after one escalation (the run safe-stopped, "silence is never approval");
  resumed by demo-approver; approval 6 approved (HUMAN): "design approved for REPLAY of existing create + resolve;
  template greenfield wording noted".

## Why REPLAY
Create and resolve were built before this run (US-001). The N05 template plans five tasks as new work; for this run
each task either points at the existing implementation or adds only what was missing for the run's own evidence.

| Task | Template description | In this run |
|---|---|---|
| t1 | API contract: additive /api/v1 change in openapi.yaml | No change. `POST /api/v1/links` and `GET /{code}` already in specs/001-agentic-url-shortener/contracts/openapi.yaml |
| t2 | Domain logic for the capability | No change. `LinkService.create` / `LinkService.resolve` (shortener/domain), `LinkController`, `RedirectController` |
| t3 | Persistence query for the capability | No change. `JdbcLinkRepository` behind `RetryingLinkRepository` (shortener/persistence) |
| t4 | Tests: contract, unit and integration | New `ScnCCreateResolveTest` (create then resolve, unknown code 404), named *Test so N09's default pattern executes it; deeper coverage already in `LinkCreationIT`, `RedirectIT`, `AppPlaneE2EIT` |
| t5 | Documentation: API notes and quickstart | This file |

Tasks t1-t3 are recorded as marker commits with no file changes, so their CODE artifacts list no files.
