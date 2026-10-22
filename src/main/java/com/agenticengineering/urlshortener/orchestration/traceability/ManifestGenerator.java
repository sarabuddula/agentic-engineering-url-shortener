package com.agenticengineering.urlshortener.orchestration.traceability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the mapping manifest from the repository: requirement IDs from the spec, ADRs and tasks from the tasks.md
 * "Req:" and "ADR:" fields, tests from the test classes that cite each ID (support classes excluded), documentation =
 * the spec, each file with the commit that last touched it. Evidence entries of a previous manifest are carried over.
 */
public final class ManifestGenerator {

    private static final Pattern TASK_ROW = Pattern.compile("^- \\[[ xX]\\] (T\\d{3}).*?\\| Req: ([^|]*)\\|\\s*ADR: ([^|]*)\\|");
    private static final Pattern ADR = Pattern.compile("ADR-\\d{3}");

    private final Path root;
    private final Path feature;
    private final CommitLookup commits;

    public ManifestGenerator(Path root, Path feature, CommitLookup commits) {
        this.root = root;
        this.feature = feature;
        this.commits = commits;
    }

    public MappingManifest generate() throws IOException {
        return generate(null);
    }

    public MappingManifest generate(MappingManifest previous) throws IOException {
        Path spec = feature.resolve("spec.md");
        List<String> catalog = RequirementCatalog.read(root.resolve(spec));
        Map<String, TreeSet<String>> adrs = new TreeMap<>();
        Map<String, TreeSet<String>> tasks = new TreeMap<>();
        Path tasksFile = root.resolve(feature.resolve("tasks.md"));
        if (Files.exists(tasksFile)) {
            for (String line : Files.readAllLines(tasksFile)) {
                Matcher row = TASK_ROW.matcher(line);
                if (row.find()) {
                    for (String id : RequirementCatalog.cited(row.group(2))) {
                        tasks.computeIfAbsent(id, k -> new TreeSet<>()).add(row.group(1));
                        Matcher adr = ADR.matcher(row.group(3));
                        while (adr.find()) {
                            adrs.computeIfAbsent(id, k -> new TreeSet<>()).add(adr.group());
                        }
                    }
                }
            }
        }
        Map<String, List<MappingManifest.FileRef>> tests = new TreeMap<>();
        for (Path file : testSources(root)) {
            String relative = relative(root, file);
            for (String id : RequirementCatalog.cited(Files.readString(file))) {
                tests.computeIfAbsent(id, k -> new ArrayList<>()).add(ref(relative));
            }
        }
        MappingManifest.FileRef specRef = ref(spec.toString().replace('\\', '/'));
        Map<String, MappingManifest.Entry> entries = new LinkedHashMap<>();
        for (String id : catalog) {
            List<MappingManifest.EvidenceRef> evidence = previous != null && previous.requirements().containsKey(id)
                    ? previous.requirements().get(id).evidence() : List.of();
            entries.put(id, new MappingManifest.Entry(List.copyOf(adrs.getOrDefault(id, new TreeSet<>())),
                    List.copyOf(tasks.getOrDefault(id, new TreeSet<>())), List.copyOf(tests.getOrDefault(id, List.of())),
                    List.of(specRef), evidence));
        }
        return new MappingManifest(feature.toString().replace('\\', '/'), entries);
    }

    private MappingManifest.FileRef ref(String relative) {
        return new MappingManifest.FileRef(relative, commits.lastCommit(Path.of(relative)).orElse("UNCOMMITTED"));
    }

    /** Test classes (names ending in Test or IT) under src/test/java, support classes excluded, in path order. */
    static List<Path> testSources(Path root) throws IOException {
        Path base = root.resolve("src/test/java");
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(p -> p.getFileName().toString().matches(".*(Test|IT)\\.java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/support/")).sorted().toList();
        }
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
