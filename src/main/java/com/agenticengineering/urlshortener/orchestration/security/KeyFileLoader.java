package com.agenticengineering.urlshortener.orchestration.security;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads the demo role keys (A-009, D-004) at startup from app.security.key-file (default .demo-keys.yml, written by
 * scripts/generate-demo-keys.sh, hashes only). Without the file the control plane rejects every request with 401.
 */
@Component
public class KeyFileLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(KeyFileLoader.class);

    private final KeyIdentityStore keys;
    private final Path keyFile;

    public KeyFileLoader(KeyIdentityStore keys, @Value("${app.security.key-file:.demo-keys.yml}") Path keyFile) {
        this.keys = keys;
        this.keyFile = keyFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isRegularFile(keyFile)) {
            LOG.warn("no key file at {} - the control plane will answer 401; run scripts/generate-demo-keys.sh", keyFile);
            return;
        }
        LOG.info("loaded {} role key identities from {}", keys.loadFromYaml(keyFile), keyFile);
    }
}
