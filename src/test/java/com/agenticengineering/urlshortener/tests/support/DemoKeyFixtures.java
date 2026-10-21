package com.agenticengineering.urlshortener.tests.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Role-key fixtures for control-plane tests (A-009, D-004). The raw keys below are deliberately non-secret, test-only
 * constants; {@code fixtures/demo-keys.test.yml} holds only their SHA-256 hashes, in the {@code .demo-keys.yml} format
 * that scripts/generate-demo-keys.sh writes. They grant nothing outside tests.
 */
public final class DemoKeyFixtures {

    private static final String RESOURCE = "/fixtures/demo-keys.test.yml";
    private static final List<String> ROLES = List.of("ENGINEER", "APPROVER", "RELEASE_OWNER", "REVIEWER");

    private DemoKeyFixtures() {
    }

    public static List<String> roles() {
        return ROLES;
    }

    /** Test-only raw key for {@code role}, for example {@code fixture-only-approver-key}. */
    public static String rawKey(String role) {
        requireRole(role);
        return "fixture-only-" + role.toLowerCase().replace('_', '-') + "-key";
    }

    /** The key_hash stored for {@code role} in the fixture file. */
    @SuppressWarnings("unchecked")
    public static String storedHash(String role) {
        requireRole(role);
        Map<String, Object> document = new Yaml().load(fixtureFileText());
        List<Map<String, Object>> keys = (List<Map<String, Object>>) document.get("keys");
        return keys.stream()
                .filter(k -> role.equals(k.get("role")))
                .map(k -> String.valueOf(k.get("key_hash")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no fixture key for role " + role));
    }

    public static String fixtureFileText() {
        try (InputStream in = DemoKeyFixtures.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void requireRole(String role) {
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("unknown role " + role);
        }
    }
}
