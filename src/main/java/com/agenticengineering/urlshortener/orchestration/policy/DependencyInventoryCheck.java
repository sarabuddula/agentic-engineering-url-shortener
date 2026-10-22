package com.agenticengineering.urlshortener.orchestration.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * DEP-01 evidence source (T082; POL-2026-10-18-v1 "every runtime dependency pinned and declared in the inventory").
 * Every dependency declared directly in the build file outside the test scope must appear in the license inventory
 * (license-maven-plugin output, docs/compliance/licenses.xml) with a fixed version. Unreadable inputs fail closed.
 */
public final class DependencyInventoryCheck {

    private DependencyInventoryCheck() {
    }

    public static Evidence evaluate(Path pom, Path inventory) {
        Map<String, String> pinned;
        List<String> declared;
        try {
            declared = runtimeDependencies(parse(pom));
            pinned = inventoryVersions(parse(inventory));
        } catch (Exception e) {
            return Evidence.notOk("DEP-01 inputs unreadable (" + pom + ", " + inventory + "): " + e.getClass().getSimpleName());
        }
        List<String> problems = new ArrayList<>();
        for (String coordinate : declared) {
            String version = pinned.get(coordinate);
            if (version == null) {
                problems.add(coordinate + " not in " + inventory);
            } else if (!isPinned(version)) {
                problems.add(coordinate + " version " + version + " not pinned");
            }
        }
        return problems.isEmpty()
                ? Evidence.ok(pom + ": " + declared.size() + " runtime dependencies declared and pinned in " + inventory)
                : Evidence.notOk(pom + ": " + String.join("; ", problems));
    }

    private static List<String> runtimeDependencies(Document pom) {
        List<String> coordinates = new ArrayList<>();
        for (Element dependencies : children(pom.getDocumentElement(), "dependencies")) {
            for (Element dependency : children(dependencies, "dependency")) {
                if (!"test".equals(text(dependency, "scope"))) {
                    coordinates.add(text(dependency, "groupId") + ":" + text(dependency, "artifactId"));
                }
            }
        }
        return coordinates;
    }

    private static Map<String, String> inventoryVersions(Document inventory) {
        Map<String, String> versions = new HashMap<>();
        for (Element dependencies : children(inventory.getDocumentElement(), "dependencies")) {
            for (Element dependency : children(dependencies, "dependency")) {
                versions.put(text(dependency, "groupId") + ":" + text(dependency, "artifactId"), text(dependency, "version"));
            }
        }
        return versions;
    }

    private static boolean isPinned(String version) {
        return !version.isBlank() && !version.contains("SNAPSHOT") && !version.contains("$") && version.chars().noneMatch(c -> "[](),".indexOf(c) >= 0);
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // no DTDs, no external entities
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        try (var in = Files.newInputStream(file)) {
            return builder.parse(in);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                found.add(e);
            }
        }
        return found;
    }

    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? "" : found.get(0).getTextContent().trim();
    }
}
