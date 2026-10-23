# Contributing

The project follows the SpecKit workflow under the constitution (`.specify/memory/constitution.md`). Every change is
traceable to a requirement, test-first, validated by an executed build, and owned by a human.

## Before you start

- Find the task in `specs/001-agentic-url-shortener/tasks.md` (Execution Order). Work without a task is out of scope.
- A change to anything already approved - requirements, architecture, schema, workflow states, security controls,
  release criteria, the API contract - is a governed change (C-005): impact analysis, then the human candidate's
  decision recorded under `specs/001-agentic-url-shortener/approvals/` and in the tasks.md Amendment Log.

## Working on a task

1. Commit a failing test first, with the expected failure reason in the commit message (`test(Txxx): red - ...`).
2. Implement until it passes (`feat(Txxx): ...` or `fix: ...`). If a test must change after its red commit, say why
   in the implementation commit.
3. Run `mvn clean verify` before every commit that is pushed; it must exit 0.
4. Cite the requirement IDs a new test class verifies in its Javadoc (FR-, NFR-, SC-, PVT-, US-, SCN-).
5. After committing, regenerate traceability: `scripts/trace-manifest.sh generate`, then `scripts/trace-manifest.sh
   check` must report CLEAN; commit the manifest separately (it records other commits, never its own).

## Commits

Small and coherent, conventional types (`feat`, `fix`, `test`, `docs`, `build`, `chore`, `refactor`) with the task ID in
the scope; the message says what changed and why, with test counts. Never rewrite published history; repair forward
and say so.

## Demonstration runs

Code enters a workflow run only as a commit whose message contains `[run <runId> task <key>]`. Label replayed work
REPLAY. Human decisions go through the approvals API with the right role key; approvals executed by test fixtures are
SIMULATED and never count as human evidence.

## Evidence and AI use

Never report a command, test or result that did not run or succeed. AI-generated artifacts need human review and
ownership; AI assistants never approve. Disclose AI assistance as the README does.

## Security

Never commit secrets or raw keys (gitleaks runs at N12). See `SECURITY.md`.
