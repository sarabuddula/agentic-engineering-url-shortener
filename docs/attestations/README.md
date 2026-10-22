# Attestations (T107)

PRV-02, LIC-02 and DEP-02 (offline) are human checks of policy POL-2026-10-18-v1. Each file in this folder is filled in
and signed by the human candidate; the AI assistant prepares the form and the checklists but never fills in the
answers or the decision. A filled attestation is recorded as a human decision (evidence class HUMAN) and referenced by
the POLICY_EVALUATION row of the check.

How to fill one in: work through the checklist, write what you looked at under "Evidence reviewed", choose ATTESTED or
NOT ATTESTED, and sign with your key ID and the date. NOT ATTESTED is a valid answer: the check then fails and the run
is NOT READY, which is the correct outcome when the evidence is not there.
