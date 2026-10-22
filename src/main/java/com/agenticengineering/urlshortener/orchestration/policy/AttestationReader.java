package com.agenticengineering.urlshortener.orchestration.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the signed attestation forms (T107; docs/attestations/{CHECK}.md). The latest decision in a form counts, so a
 * re-review supersedes the earlier decision: only "ATTESTED" with a signing key ID yields an attestation, referenced as
 * {form}@{reviewed commit}. NOT ATTESTED, unsigned or missing forms yield none (the check fails closed).
 */
public final class AttestationReader {

    private static final List<String> CHECKS = List.of("PRV-02", "LIC-02", "DEP-02");

    private AttestationReader() {
    }

    public static Map<String, PolicyInput.Attestation> read(Path dir) throws IOException {
        Map<String, PolicyInput.Attestation> attestations = new LinkedHashMap<>();
        for (String check : CHECKS) {
            Path form = dir.resolve(check + ".md");
            if (!Files.exists(form)) {
                continue;
            }
            String decision = null;
            String keyId = null;
            String commit = null;
            for (String line : Files.readAllLines(form)) {
                if (line.startsWith("ATTESTED / NOT ATTESTED:")) {
                    decision = value(line);
                } else if (line.startsWith("Attested by")) {
                    keyId = value(line);
                } else if (line.startsWith("Reviewed commit:")) {
                    commit = value(line);
                }
            }
            if ("ATTESTED".equals(decision) && keyId != null && !keyId.isBlank() && !keyId.startsWith("_")) {
                attestations.put(check, new PolicyInput.Attestation(keyId, form.toString().replace('\\', '/') + "@" + commit));
            }
        }
        return attestations;
    }

    private static String value(String line) {
        return line.substring(line.lastIndexOf(':') + 1).trim();
    }
}
