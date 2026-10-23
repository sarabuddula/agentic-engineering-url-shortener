# G8 final submission (superseding) and T114 closure - 2026-10-22

Human decisions by the candidate, RELEASE_OWNER (G8). Recorded words: "yes, go head", in reply to the proposed
texts below, which were shown in full beforehand. Recorded by Claude Code; the decisions are the candidate's.

## G8 - final submission
Decision (proposed text approved): "I approve G8: submit assessment-submission-v1.0 on the final commit; it
supersedes v1.0-submission, which lacked the final summary and architecture overview."

- Reason: `v1.0-submission` (28bb1aa, G8 recorded in d5add0c) predates the final engineering summary and architecture
  overview (f1228df), which the assignment requires (sections 4.8 and 5), and the later documentation and evidence
  records (a66a648 to ddea137).
- The new annotated tag `assessment-submission-v1.0` (execution guide section 28) points at the commit that contains this
  record. `v1.0-submission` is kept unchanged as the earlier point; published history is not rewritten.
- Release decision unchanged: READY WITH ACCEPTED LIMITATIONS (T099, run-85f0038b, REPLAY). No code changed after it:
  every commit since 28bb1aa touches documentation and records only.
- Validation from a fresh checkout (guide section 28): `docs/evidence/t092-reviewer-walkthrough.md`, "Re-run on the
  final documentation commit" - 302 unit and 122 integration tests, exit 0; all eight runs match their exports;
  traceability CLEAN. Assessment: `docs/evidence/final-independent-assessment.md` (PASS).

## T114 - red-first audit
Decision (proposed text approved): "T114 closed 2026-10-22 by the human candidate: the red-first rule was applied in
every implementation task; docs/evidence/red-first-audit.md shows the failing-test commit before the implementation
commit for each task, with the deviations (T006-T008) disclosed."
