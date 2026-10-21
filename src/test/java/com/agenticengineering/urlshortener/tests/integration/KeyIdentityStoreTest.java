package com.agenticengineering.urlshortener.tests.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticengineering.urlshortener.orchestration.security.KeyHashes;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentity;
import com.agenticengineering.urlshortener.orchestration.security.KeyIdentityStore;
import com.agenticengineering.urlshortener.tests.support.DemoKeyFixtures;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** T023: role-scoped key identities stored as SHA-256 hashes, compared in constant time (FR-045, D-004, ADR-008). */
@JdbcTest
@Import(KeyIdentityStore.class)
class KeyIdentityStoreTest {

    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/demo-keys.test.yml");

    @Autowired
    private KeyIdentityStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void loadFixtureKeys() {
        assertThat(store.loadFromYaml(FIXTURE)).isEqualTo(4);
    }

    @Test
    void constantTimeCompareMatchesOnlyTheSameHash() {
        String hash = KeyHashes.sha256Hex("abc");

        assertThat(KeyHashes.matches("abc", hash)).isTrue();
        assertThat(KeyHashes.matches("abd", hash)).isFalse();
        assertThat(KeyHashes.matches("abc", hash.substring(0, 63))).isFalse();
        assertThat(KeyHashes.matches(null, hash)).isFalse();
        assertThat(KeyHashes.matches("", hash)).isFalse();
    }

    @Test
    void validKeyResolvesToItsIdAndRole() {
        KeyIdentity approver = store.authenticate(DemoKeyFixtures.rawKey("APPROVER")).orElseThrow();

        assertThat(approver.keyId()).isEqualTo("test-approver");
        assertThat(approver.role()).isEqualTo(KeyIdentity.Role.APPROVER);
        assertThat(approver.hasAnyRole(KeyIdentity.Role.APPROVER, KeyIdentity.Role.RELEASE_OWNER)).isTrue();
        assertThat(approver.hasAnyRole(KeyIdentity.Role.ENGINEER)).isFalse();
    }

    @Test
    void unknownBlankOrMissingKeysDoNotAuthenticate() {
        assertThat(store.authenticate("not-a-registered-key")).isEmpty();
        assertThat(store.authenticate("")).isEmpty();
        assertThat(store.authenticate(null)).isEmpty();
    }

    @Test
    void onlyHashesAreStoredAndTheIdentityExposesNoKeyMaterial() {
        String rawKey = DemoKeyFixtures.rawKey("ENGINEER");
        KeyIdentity engineer = store.authenticate(rawKey).orElseThrow();

        assertThat(jdbc.queryForList("SELECT KEY_HASH FROM KEY_IDENTITY", String.class))
                .allMatch(h -> h.matches("[0-9a-f]{64}"))
                .doesNotContain(rawKey);
        assertThat(Arrays.stream(KeyIdentity.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("keyId", "role", "label");
        assertThat(engineer.toString()).doesNotContain(rawKey).doesNotContain(KeyHashes.sha256Hex(rawKey));
    }

    @Test
    void reloadingTheSameFileIsIdempotent() {
        assertThat(store.loadFromYaml(FIXTURE)).isEqualTo(4);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM KEY_IDENTITY", Integer.class)).isEqualTo(4);
    }
}
