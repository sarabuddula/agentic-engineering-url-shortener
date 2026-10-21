package com.agenticengineering.urlshortener.orchestration.security;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

/**
 * KEY_IDENTITY store (T023; FR-045, D-004, ADR-008): role-scoped demo keys held as SHA-256 hashes. Authentication
 * compares the presented key against every stored hash in constant time, without stopping at the first match, so the
 * response time does not reveal which key was close.
 */
@Component
public class KeyIdentityStore {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;

    public KeyIdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KeyIdentity> authenticate(String rawKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT KEY_ID, ROLE, KEY_HASH, LABEL FROM KEY_IDENTITY");
        KeyIdentity match = null;
        for (Map<String, Object> row : rows) {
            boolean same = KeyHashes.matches(rawKey, (String) row.get("KEY_HASH"));
            if (same && match == null) {
                match = new KeyIdentity((String) row.get("KEY_ID"), KeyIdentity.Role.valueOf((String) row.get("ROLE")),
                        (String) row.get("LABEL"));
            }
        }
        return Optional.ofNullable(match);
    }

    /**
     * Loads key IDs, roles and hashes from a file in the {@code .demo-keys.yml} format written by
     * scripts/generate-demo-keys.sh; existing key IDs are replaced. Returns the number of keys loaded.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public int loadFromYaml(Path file) {
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(file)) {
            document = new Yaml().load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read key file " + file, e);
        }
        List<Map<String, Object>> keys = (List<Map<String, Object>>) document.get("keys");
        if (keys == null) {
            throw new IllegalArgumentException("key file has no 'keys' list: " + file);
        }
        for (Map<String, Object> key : keys) {
            String keyId = String.valueOf(key.get("key_id"));
            KeyIdentity.Role role = KeyIdentity.Role.valueOf(String.valueOf(key.get("role")));
            String hash = String.valueOf(key.get("key_hash"));
            if (!HASH.matcher(hash).matches()) {
                throw new IllegalArgumentException("key " + keyId + " has no SHA-256 hex hash");
            }
            Object label = key.get("label");
            jdbc.update("MERGE INTO KEY_IDENTITY (KEY_ID, ROLE, KEY_HASH, LABEL) KEY (KEY_ID) VALUES (?, ?, ?, ?)",
                    keyId, role.name(), hash, label == null ? null : label.toString());
        }
        return keys.size();
    }
}
