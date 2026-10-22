# Attestation LIC-02 - Licensing

Check (POL-2026-10-18-v1): third-party notices present in the distribution docs.
Inventory: docs/compliance/licenses.xml (license-maven-plugin output, every resolved dependency with its license).

## Checklist (answer each: YES / NO / NOT CHECKED, with a note)
1. A third-party notices file exists in the distribution docs (for example docs/compliance/THIRD-PARTY-NOTICES.md or a
   section in README.md) and lists every runtime dependency in licenses.xml: ____
2. Every license in licenses.xml is acceptable for this use (Apache-2.0, MIT, EPL-2.0, BSD, LGPL used as a library, ...);
   none is unknown or copyleft-incompatible: ____
3. The notices are current with the pom.xml at the reviewed commit (scripts/trace-manifest.sh check and DEP-01 PASS): ____
4. Anything else found: ____

## Evidence reviewed
(files, commands, commit SHA you reviewed)

## Decision
ATTESTED / NOT ATTESTED: ____
Limitations or follow-ups: ____

Attested by (key ID, never a raw key): ____
Role: RELEASE_OWNER
Date: ____
Reviewed commit: ____
