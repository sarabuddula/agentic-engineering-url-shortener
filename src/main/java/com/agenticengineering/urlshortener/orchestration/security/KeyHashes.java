package com.agenticengineering.urlshortener.orchestration.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 key hashing and constant-time comparison (D-004, ADR-008). Raw keys are never stored or logged. */
public final class KeyHashes {

    private KeyHashes() {
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when {@code rawKey} hashes to {@code storedHash}; the byte comparison takes the same time for any mismatch. */
    public static boolean matches(String rawKey, String storedHash) {
        if (rawKey == null || rawKey.isEmpty() || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256Hex(rawKey).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
