package com.agenticengineering.urlshortener.shared.api;

/** A named resource does not exist: 404 with a stable code (for example RUN_NOT_FOUND). */
public final class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
