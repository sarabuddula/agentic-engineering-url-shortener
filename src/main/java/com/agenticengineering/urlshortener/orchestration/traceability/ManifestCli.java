package com.agenticengineering.urlshortener.orchestration.traceability;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line for the mapping manifest (run by scripts/trace-manifest.sh): {@code generate} rewrites
 * {feature}/traceability/mapping-manifest.yml from the repository (keeping evidence entries); {@code check} prints the
 * orphan report and exits 1 when it is not clean; {@code matrix} also writes the traceability matrix
 * {feature}/traceability/matrix.md (T090).
 */
public final class ManifestCli {

    private ManifestCli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length > 0 ? args[0] : "check";
        Path root = Path.of(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
        Path feature = Path.of(args.length > 2 ? args[2] : "specs/001-agentic-url-shortener");
        Path file = root.resolve(feature).resolve("traceability/mapping-manifest.yml");
        CommitLookup commits = new GitCommitLookup(root);
        MappingManifest previous = Files.exists(file) ? MappingManifest.fromYaml(Files.readString(file)) : null;
        if (command.equals("generate")) {
            MappingManifest manifest = new ManifestGenerator(root, feature, commits).generate(previous);
            Files.createDirectories(file.getParent());
            Files.writeString(file, manifest.toYaml());
            System.out.println("wrote " + root.relativize(file) + " (" + manifest.requirements().size() + " requirements)");
        }
        if (previous == null && !Files.exists(file)) {
            System.err.println("no manifest at " + file + "; run generate first");
            System.exit(2);
        }
        ManifestChecker.Report report = new ManifestChecker(root, commits).check(MappingManifest.fromYaml(Files.readString(file)));
        System.out.println(report.describe());
        System.out.println(report.clean() ? "traceability: CLEAN" : "traceability: ORPHANS FOUND");
        if (command.equals("matrix")) {
            Path matrix = file.resolveSibling("matrix.md");
            Files.writeString(matrix, TraceabilityMatrix.render(MappingManifest.fromYaml(Files.readString(file)), report, root));
            System.out.println("wrote " + root.relativize(matrix));
        }
        if (command.equals("check") && !report.clean()) {
            System.exit(1);
        }
    }
}
