# Human Decision 2026-10-22: CHK047 - amend and enforce the per-node executor whitelist

Date: 2026-10-22
Approver: Shilpa (human candidate)
Recorded words: "continue", given in reply to the T096 finding CHK047 with the options (a) amend the whitelist with the
two legitimate operations and enforce it in code, or (b) accept it as a disclosed limitation, and a recommendation for
(a) (prepared with AI assistance; the AI assistant does not approve). Recorded as choosing (a).

## Decision (C-005, governed change of plan section 3, amendment of 2026-10-19)
1. The whitelist gains two operations that the implementation needs and that were reviewed as legitimate:
   N04 adds AQ (it requests the C-005 CHANGE_APPROVAL with the impact analysis, T119);
   N12 adds TS (it runs the test classes that evidence SEC-01, SEC-02, PRV-01, AUD-01 and CMP-02, option (a) of 2026-10-22).
2. The whitelist is enforced in code: an operation outside the executing node's list is refused, audited
   (actor_type AGENT, result BLOCKED) and fails the stage. Operations started by people through the API are outside
   any stage executor and are governed by role checks, not by the whitelist.
