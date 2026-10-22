package com.agenticengineering.urlshortener.orchestration.traceability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The requirement IDs a spec defines, in order of definition: FR, NFR and SC list items, PVT targets, user stories
 * (US-NNN in the story heading) and demonstration scenarios (SCN-A..C). Mentions elsewhere do not define an ID.
 */
public final class RequirementCatalog {

    /** Any requirement ID as cited in tests, tasks and documents. */
    public static final Pattern CITATION = Pattern.compile("\\b(?:(?:FR|NFR|SC|PVT|US)-\\d{3}|SCN-[A-C])\\b");

    private static final List<Pattern> DEFINITIONS = List.of(
            Pattern.compile("^- \\*\\*((?:FR|NFR|SC)-\\d{3})\\b"),
            Pattern.compile("^- (PVT-\\d{3}):"),
            Pattern.compile("^### User Story .*\\((US-\\d{3})\\)"),
            Pattern.compile("^\\*\\*Scenario (SCN-[A-C])\\b"));

    private RequirementCatalog() {
    }

    public static List<String> read(Path spec) throws IOException {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(spec)) {
            for (Pattern definition : DEFINITIONS) {
                Matcher m = definition.matcher(line);
                if (m.find()) {
                    ids.add(m.group(1));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    public static LinkedHashSet<String> cited(String text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(text);
        while (m.find()) {
            ids.add(m.group());
        }
        return ids;
    }
}
