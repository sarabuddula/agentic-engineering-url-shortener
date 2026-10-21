#!/usr/bin/env bash
# DEP-02 vulnerability scan (T106). Runs OWASP DependencyCheck (profile dep-scan, plugin pinned in pom.xml).
#
# Usage: NVD_API_KEY=<key> scripts/dep-scan.sh
#
# The key is read by the plugin from the NVD_API_KEY environment variable; it is never passed on the command line,
# printed, or written to a file. Report: target/dependency-check/dependency-check-report.{html,json}.
# If the NVD data cannot be updated (no key, offline, NVD unavailable), DEP-02 degrades to attestation (plan section 8):
# this script writes docs/compliance/dep-02-degradation.md with the date and cause, and exits 2.
# Must run on macOS bash 3.2.
set -u

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root" || exit 1
log="target/dep-scan.log"
mkdir -p target

if mvn -B -Pdep-scan -DskipTests verify > "$log" 2>&1; then
    echo "DEP-02 scan completed; report in target/dependency-check/"
    exit 0
fi

if grep -q "Error updating the NVD Data" "$log"; then
    cause="$(grep -m1 -E 'NvdApiException|UpdateException|UnknownHost|Connect' "$log" | sed -E 's/^[[:space:]]+//' | cut -c1-200)"
    note="docs/compliance/dep-02-degradation.md"
    {
        echo "# DEP-02 Degradation Note"
        echo
        echo "Recorded by scripts/dep-scan.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)."
        echo
        echo "- Check: DEP-02 (dependency vulnerability scan clean), data-model.md policy inventory."
        echo "- Result: the scan could not update its NVD vulnerability data, so no automated result exists."
        echo "- Cause (first matching log line): ${cause:-see target/dep-scan.log}"
        if [ -z "${NVD_API_KEY:-}" ]; then
            echo "- NVD_API_KEY was not set. dependency-check-maven 13.x requires an NVD API key to update."
        fi
        echo "- Consequence: DEP-02 degrades to attestation (plan section 8); the attestation is recorded at T107 as a"
        echo "  human decision. This note is not a scan result and does not claim the dependencies are vulnerability-free."
    } > "$note"
    echo "DEP-02 degraded: wrote $note" >&2
    exit 2
fi

echo "dependency scan failed for another reason; see $log" >&2
exit 1
