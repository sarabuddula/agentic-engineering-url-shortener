# Human Decisions 2026-10-22: T084 checkpoint acceptance and T099 final release decision

Date: 2026-10-22
Approver: Shilpa (human candidate), RELEASE_OWNER (demo-release-owner) for the release decision
Basis: docs/evidence/t084-acceptance-list.md (prepared with AI assistance; the AI assistant does not approve)

## T084 - end-of-Day-2 checkpoint
Recorded words: "T084: accept all. Noted: T110 coverage split across ResumeApiIT and ReplanApiIT; T106 online scan
not run (degraded path and DEP-02 attestation); T087 ended SAFE_STOPPED at the completion gate; T089 and T117 reduced
by L-SC-07. Add to the list that the demonstration runs predate the whitelist enforcement (0bc2f00)."
Decision: every task in the acceptance list is accepted, including T073, T093, T094 and T117 ticked earlier by the
scenario session, and the folded tasks T112 (closed with T090) and T116 (closed with T089, SCN-A.4 part cut, L-SC-07).
The whitelist note is in the list (commit 18c67ae).

## T099 - final release decision
Recorded words: "my final release decision is READY WITH ACCEPTED LIMITATIONS, based on run-85f0038b. The six
limitations I accepted at G6 (L-SC-01 to 04, 06, 07) apply to the product. SCN-A's LIVE run did not reach COMPLETED;
its completed run is REPLAY."
Decision: the release is READY WITH ACCEPTED LIMITATIONS, based on run run-85f0038b (system recommendation
READY_WITH_ACCEPTED_LIMITATIONS, G7 release authorization by the candidate, run COMPLETED, chain verified). The six
individually accepted limitations L-SC-01 to L-SC-04, L-SC-06 and L-SC-07 apply to the product, not to one run; the
plain READY of run-4738769c reflects only that no limitations were attached to that run. Disclosed: the SCN-A LIVE run
(run-934a50a1) did not reach COMPLETED (stopped at the completion gate); the completed SCN-A run is REPLAY
(run-4738769c).

## T100 - G8 final submission
Recorded words: "only after the other session confirms T099 is recorded: approve G8". The tag is created on the commit
that contains this record.
