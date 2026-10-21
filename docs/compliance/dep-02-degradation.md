# DEP-02 Degradation Note

Recorded by scripts/dep-scan.sh on 2026-10-21T23:19:00Z.

- Check: DEP-02 (dependency vulnerability scan clean), data-model.md policy inventory.
- Result: the scan could not update its NVD vulnerability data, so no automated result exists.
- Cause (first matching log line): org.owasp.dependencycheck.data.update.exception.UpdateException: Error updating the NVD Data
- NVD_API_KEY was not set. dependency-check-maven 13.x requires an NVD API key to update.
- Consequence: DEP-02 degrades to attestation (plan section 8); the attestation is recorded at T107 as a
  human decision. This note is not a scan result and does not claim the dependencies are vulnerability-free.
