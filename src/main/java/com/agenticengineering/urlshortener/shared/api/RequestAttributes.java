package com.agenticengineering.urlshortener.shared.api;

/** Request attribute names shared by both planes' HTTP adapters (correlation id T022, authenticated key T058). */
public final class RequestAttributes {

    /** Correlation id set by the request-correlation filter. */
    public static final String REQUEST_ID = "app.requestId";

    /** Key ID of the authenticated caller (never the key itself). */
    public static final String KEY_ID = "app.keyId";

    /** Role of the authenticated caller. */
    public static final String KEY_ROLE = "app.keyRole";

    private RequestAttributes() {
    }
}
