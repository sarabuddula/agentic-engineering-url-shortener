package com.agenticengineering.urlshortener.orchestration.stages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only git queries on the workspace for the implementation and documentation stages (argument lists, no shell). */
final class Workspace {

    private Workspace() {
    }

    /** Output of git in the workspace, or null when git fails (not a repository, git missing). */
    static String git(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
