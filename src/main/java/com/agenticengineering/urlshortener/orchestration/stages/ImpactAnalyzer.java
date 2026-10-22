package com.agenticengineering.urlshortener.orchestration.stages;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * N04 impact identification by static scan (T068; FR-041, SCN-B). A seed (src/main/resources/impact/seeds.json) maps a
 * changed concept to code-level anchors; the scan then lists the application sources that mention an anchor
 * (components), the sources that reference those classes (indirect components), configuration keys and contract
 * operations (interfaces), schema columns (data flows), tests, and documentation. Regression risks, rollout and rollback
 * come from the seed plus the scan's counts. A change that matches no seed yields no report: its impact is unknown and
 * the stage fails rather than letting implementation start. Deterministic: the same repository state gives the same
 * report.
 */
public final class ImpactAnalyzer {

    public record Seed(String concept, String trigger, List<String> anchors,
            @com.fasterxml.jackson.annotation.JsonProperty("regression_risks") List<String> regressionRisks, List<String> rollout,
            List<String> rollback) {
    }

    private static final Pattern CREATE_TABLE = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+)");
    private static final Pattern METHOD = Pattern.compile("^(get|post|put|patch|delete):");

    private final Path root;
    private final List<Seed> seeds;

    public ImpactAnalyzer(Path root, List<Seed> seeds) {
        this.root = root;
        this.seeds = List.copyOf(seeds);
    }

    public static List<Seed> defaultSeeds() {
        try (InputStream in = ImpactAnalyzer.class.getResourceAsStream("/impact/seeds.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<List<Seed>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Map<String, Object>> analyze(String requirement) throws IOException {
        String lower = requirement.toLowerCase(Locale.ROOT);
        Optional<Seed> seed = seeds.stream().filter(s -> Pattern.compile(s.trigger()).matcher(lower).find()).findFirst();
        if (seed.isEmpty()) {
            return Optional.empty();
        }
        List<Pattern> anchors = seed.get().anchors().stream().map(Pattern::compile).toList();

        TreeSet<String> components = new TreeSet<>();
        List<String> componentNames = new ArrayList<>();
        for (Path file : files("src/main/java", ".java")) {
            if (mentions(Files.readString(file), anchors)) {
                components.add(relative(file));
                componentNames.add(simpleName(file));
            }
        }
        List<Pattern> references = componentNames.stream().map(n -> Pattern.compile("\\b" + n + "\\b")).toList();
        TreeSet<String> indirect = new TreeSet<>();
        for (Path file : files("src/main/java", ".java")) {
            if (!components.contains(relative(file)) && mentions(Files.readString(file), references)) {
                indirect.add(relative(file));
            }
        }
        TreeSet<String> tests = new TreeSet<>();
        for (Path file : files("src/test/java", ".java")) {
            String text = Files.readString(file);
            if (mentions(text, anchors) || mentions(text, references)) {
                tests.add(relative(file));
            }
        }
        TreeSet<String> documentation = new TreeSet<>();
        for (String dir : List.of("specs", "docs")) {
            for (Path file : files(dir, ".md", ".yaml", ".yml")) {
                if (mentions(Files.readString(file), anchors)) {
                    documentation.add(relative(file));
                }
            }
        }
        Path readme = root.resolve("README.md");
        if (Files.exists(readme) && mentions(Files.readString(readme), anchors)) {
            documentation.add("README.md");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("requirement", requirement);
        report.put("concept", seed.get().concept());
        report.put("analyzer", "static-scan-v1 (seeds: src/main/resources/impact/seeds.json)");
        report.put("anchors", seed.get().anchors());
        report.put("components", List.copyOf(components));
        report.put("indirect_components", List.copyOf(indirect));
        report.put("interfaces", interfaces(anchors));
        report.put("data_flows", dataFlows(anchors));
        report.put("tests", List.copyOf(tests));
        report.put("documentation", List.copyOf(documentation));
        List<String> risks = new ArrayList<>(seed.get().regressionRisks());
        risks.add(tests.size() + " test files reference the affected components or the changed value; tests asserting the"
                + " superseded value are updated under the approved change, all others must pass unchanged");
        report.put("regression_risks", risks);
        report.put("rollout", seed.get().rollout());
        report.put("rollback", seed.get().rollback());
        return Optional.of(report);
    }

    /** Configuration keys (application.yml) and contract operations or schemas (openapi.yaml) that mention an anchor. */
    private List<String> interfaces(List<Pattern> anchors) throws IOException {
        TreeSet<String> found = new TreeSet<>();
        Path config = root.resolve("src/main/resources/application.yml");
        if (Files.exists(config)) {
            List<String> lines = Files.readAllLines(config);
            for (int i = 0; i < lines.size(); i++) {
                if (mentions(lines.get(i), anchors)) {
                    List<String> path = new ArrayList<>(ancestors(lines, i).reversed().stream().map(ImpactAnalyzer::key).toList());
                    path.add(key(lines.get(i)));
                    found.add("configuration " + String.join(".", path) + " (application.yml)");
                }
            }
        }
        for (Path contract : files("specs", "openapi.yaml")) {
            List<String> lines = Files.readAllLines(contract);
            for (int i = 0; i < lines.size(); i++) {
                if (!mentions(lines.get(i), anchors)) {
                    continue;
                }
                List<String> up = ancestors(lines, i);
                operation(up).ifPresent(op -> found.add(op + " (contract)"));
                schema(up).ifPresent(schema -> {
                    found.add("schema " + schema + " (contract)");
                    String ref = "#/components/schemas/" + schema;
                    for (int j = 0; j < lines.size(); j++) {
                        if (lines.get(j).contains(ref)) {
                            operation(ancestors(lines, j)).ifPresent(op -> found.add(op + " (contract, via " + schema + ")"));
                        }
                    }
                });
            }
        }
        return List.copyOf(found);
    }

    /** Schema columns whose definition or comment mentions an anchor, with the comment as the flow description. */
    private List<String> dataFlows(List<Pattern> anchors) throws IOException {
        Path schema = root.resolve("src/main/resources/schema.sql");
        TreeSet<String> found = new TreeSet<>();
        if (!Files.exists(schema)) {
            return List.of();
        }
        String table = null;
        for (String line : Files.readAllLines(schema)) {
            var create = CREATE_TABLE.matcher(line);
            if (create.find()) {
                table = create.group(1);
            } else if (table != null && !line.isBlank() && !line.trim().startsWith("--") && !line.trim().startsWith("CONSTRAINT")
                    && mentions(line, anchors)) {
                String column = line.trim().split("\\s+")[0];
                int comment = line.indexOf("--");
                found.add(table + "." + column + (comment >= 0 ? " (" + line.substring(comment + 2).trim() + ")" : ""));
            }
        }
        return List.copyOf(found);
    }

    private static Optional<String> operation(List<String> ancestors) {
        String method = null;
        for (String line : ancestors) {
            String trimmed = line.trim();
            if (method == null && METHOD.matcher(trimmed).find()) {
                method = trimmed.substring(0, trimmed.indexOf(':')).toUpperCase(Locale.ROOT);
            } else if (method != null && trimmed.startsWith("/")) {
                return Optional.of(method + " " + trimmed.substring(0, trimmed.indexOf(':')));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> schema(List<String> ancestors) {
        for (int i = 0; i + 1 < ancestors.size(); i++) {
            if (ancestors.get(i + 1).trim().equals("schemas:")) {
                return Optional.of(key(ancestors.get(i)));
            }
        }
        return Optional.empty();
    }

    /** Enclosing YAML lines, nearest first (each less indented than the previous). */
    private static List<String> ancestors(List<String> lines, int index) {
        List<String> found = new ArrayList<>();
        int indent = indent(lines.get(index));
        for (int j = index - 1; j >= 0 && indent > 0; j--) {
            String line = lines.get(j);
            if (line.isBlank() || line.trim().startsWith("#") || line.trim().startsWith("-")) {
                continue;
            }
            if (indent(line) < indent) {
                found.add(line);
                indent = indent(line);
            }
        }
        return found;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String key(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(0, colon);
    }

    private List<Path> files(String dir, String... suffixes) throws IOException {
        Path base = root.resolve(dir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> Stream.of(suffixes).anyMatch(s -> p.getFileName().toString().endsWith(s)))
                    .sorted().toList();
        }
    }

    private static boolean mentions(String text, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(text).find());
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String simpleName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }
}
