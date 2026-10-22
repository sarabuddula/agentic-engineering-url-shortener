package com.agenticengineering.urlshortener.orchestration.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SEC-03 secret scan (data-model: gitleaks, offline): scans the workspace - its git history when it is a repository,
 * the files otherwise - with findings redacted. No leaks passes; leaks, a missing tool or a timeout fail closed. The
 * repository's .gitleaks.toml allow-list applies.
 */
public final class SecretScan {

    private SecretScan() {
    }

    public static Evidence run(Path workspace, String command) {
        List<String> args = new ArrayList<>(List.of(command, "detect", "--source", workspace.toString(), "--no-banner", "--redact",
                "--exit-code", "1"));
        boolean git = Files.isDirectory(workspace.resolve(".git"));
        if (!git) {
            args.add("--no-git");
        }
        try {
            Process process = new ProcessBuilder(args).directory(workspace.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Evidence.notOk("secret scan timed out after 180 s");
            }
            String scope = git ? "git history" : "files";
            return process.exitValue() == 0 ? Evidence.ok(command + ": no leaks in the workspace " + scope)
                    : Evidence.notOk(command + ": leaks found in the workspace " + scope + " (exit " + process.exitValue() + ", redacted)");
        } catch (IOException e) {
            return Evidence.notOk("secret scanner unavailable (" + command + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Evidence.notOk("secret scan interrupted");
        }
    }
}
