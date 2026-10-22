package com.agenticengineering.urlshortener.orchestration.traceability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** CommitLookup backed by the git command line (argument lists, no shell). */
public final class GitCommitLookup implements CommitLookup {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private final Path root;

    public GitCommitLookup(Path root) {
        this.root = root;
    }

    @Override
    public Optional<String> lastCommit(Path relative) {
        String sha = run("log", "-1", "--format=%H", "--", relative.toString().replace('\\', '/'));
        return SHA.matcher(sha).matches() ? Optional.of(sha) : Optional.empty();
    }

    @Override
    public boolean exists(String sha) {
        return SHA.matcher(sha).matches() && run("cat-file", "-t", sha).equals("commit");
    }

    private String run(String... args) {
        try {
            List<String> command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? out : "";
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
