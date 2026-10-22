#!/usr/bin/env bash
# Mapping manifest generator and checker (T105, plan section 13).
#
# Usage: scripts/trace-manifest.sh generate   rewrite specs/001-agentic-url-shortener/traceability/mapping-manifest.yml
#        scripts/trace-manifest.sh check      print the orphan report; exit 1 when not clean
#
# generate keeps evidence entries already in the manifest. Commits recorded are the last commit touching each file,
# never the manifest's own: run generate after committing the files it describes, then commit the manifest.
# Must run on macOS bash 3.2.
set -u

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root" || exit 1
mkdir -p target
mvn -B -q compile > target/trace-manifest.log 2>&1 || { echo "compile failed; see target/trace-manifest.log"; exit 3; }
mvn -B -q dependency:build-classpath -Dmdep.outputFile=target/trace-classpath.txt >> target/trace-manifest.log 2>&1 \
    || { echo "classpath resolution failed; see target/trace-manifest.log"; exit 3; }
java -cp "target/classes:$(cat target/trace-classpath.txt)" \
    com.agenticengineering.urlshortener.orchestration.traceability.ManifestCli "${1:-check}" "$repo_root"
