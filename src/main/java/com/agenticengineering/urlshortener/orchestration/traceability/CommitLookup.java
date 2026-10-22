package com.agenticengineering.urlshortener.orchestration.traceability;

import java.nio.file.Path;
import java.util.Optional;

/** Commit facts the manifest records and the checker verifies. */
public interface CommitLookup {

    /** The SHA of the last commit that touched the file (path relative to the repository root). */
    Optional<String> lastCommit(Path relative);

    boolean exists(String sha);
}
