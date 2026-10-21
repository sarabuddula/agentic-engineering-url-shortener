package com.agenticengineering.urlshortener.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Demonstration requirement texts for SCN-A, SCN-B and SCN-C (plan section 11). These are proposed demonstration
 * fixtures for the orchestration system, not approved product requirements.
 */
public final class ScenarioFixtures {

    private ScenarioFixtures() {
    }

    /** SCN-A greenfield, well-defined: link status lookup. */
    public static String scnA() {
        return load("scn-a.txt");
    }

    /** SCN-B brownfield change: default TTL from 7 days to 24 hours (governed change of A-006). */
    public static String scnB() {
        return load("scn-b.txt");
    }

    /** SCN-C ambiguous requirement. */
    public static String scnC() {
        return load("scn-c.txt");
    }

    private static String load(String name) {
        String resource = "/fixtures/scenarios/" + name;
        try (InputStream in = ScenarioFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing scenario fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
