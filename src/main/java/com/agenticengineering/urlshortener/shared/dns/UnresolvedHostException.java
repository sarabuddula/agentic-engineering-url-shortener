package com.agenticengineering.urlshortener.shared.dns;

/** A target host could not be resolved; the create request is rejected (D-003, plan section 8). */
public class UnresolvedHostException extends RuntimeException {

    public UnresolvedHostException(String host, String reason) {
        super("Unresolved host '" + host + "': " + reason);
    }

    public UnresolvedHostException(String host, String reason, Throwable cause) {
        super("Unresolved host '" + host + "': " + reason, cause);
    }
}
