package com.agenticengineering.urlshortener.orchestration.traceability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The mapping manifest (T105; plan section 13): per requirement ID, the ADRs and tasks that address it, the tests that
 * cite it and the documentation that defines it (each with the SHA of the commit that last touched the file, never the
 * manifest's own), and evidence files with SHA-256 hashes. Stored as YAML in the feature directory.
 */
public record MappingManifest(String feature, Map<String, Entry> requirements) {

    public record FileRef(String path, String commit) {
    }

    public record EvidenceRef(String path, String sha256) {
    }

    public record Entry(List<String> adrs, List<String> tasks, List<FileRef> tests, List<FileRef> docs, List<EvidenceRef> evidence) {
    }

    public MappingManifest withEvidence(String requirement, List<EvidenceRef> evidence) {
        Map<String, Entry> copy = new LinkedHashMap<>(requirements);
        Entry e = copy.get(requirement);
        copy.put(requirement, new Entry(e.adrs(), e.tasks(), e.tests(), e.docs(), List.copyOf(evidence)));
        return new MappingManifest(feature, copy);
    }

    public MappingManifest withTest(String requirement, FileRef test) {
        Map<String, Entry> copy = new LinkedHashMap<>(requirements);
        Entry e = copy.get(requirement);
        List<FileRef> tests = new ArrayList<>(e.tests());
        tests.add(test);
        copy.put(requirement, new Entry(e.adrs(), e.tasks(), List.copyOf(tests), e.docs(), e.evidence()));
        return new MappingManifest(feature, copy);
    }

    public String toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("manifest_version", 1);
        root.put("feature", feature);
        Map<String, Object> entries = new LinkedHashMap<>();
        requirements.forEach((id, e) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("adrs", e.adrs());
            entry.put("tasks", e.tasks());
            entry.put("tests", e.tests().stream().map(f -> map("path", f.path(), "commit", f.commit())).toList());
            entry.put("docs", e.docs().stream().map(f -> map("path", f.path(), "commit", f.commit())).toList());
            entry.put("evidence", e.evidence().stream().map(f -> map("path", f.path(), "sha256", f.sha256())).toList());
            entries.put(id, entry);
        });
        root.put("requirements", entries);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setWidth(160);
        return "# Mapping manifest (T105, plan section 13). Generated: scripts/trace-manifest.sh generate (evidence entries are kept).\n"
                + "# Commits are the last commit touching each file, never this manifest's own commit.\n"
                + new Yaml(options).dump(root);
    }

    @SuppressWarnings("unchecked")
    public static MappingManifest fromYaml(String yaml) {
        Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, Object> requirements = (Map<String, Object>) root.getOrDefault("requirements", Map.of());
        requirements.forEach((id, value) -> {
            Map<String, Object> e = (Map<String, Object>) value;
            entries.put(id, new Entry(strings(e.get("adrs")), strings(e.get("tasks")), files(e.get("tests")), files(e.get("docs")),
                    ((List<Map<String, Object>>) e.getOrDefault("evidence", List.of())).stream()
                            .map(m -> new EvidenceRef((String) m.get("path"), (String) m.get("sha256"))).toList()));
        });
        return new MappingManifest((String) root.get("feature"), entries);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return value == null ? List.of() : List.copyOf((List<String>) value);
    }

    @SuppressWarnings("unchecked")
    private static List<FileRef> files(Object value) {
        return value == null ? List.of() : ((List<Map<String, Object>>) value).stream()
                .map(m -> new FileRef((String) m.get("path"), (String) m.get("commit"))).toList();
    }

    private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
