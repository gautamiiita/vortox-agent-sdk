package com.vortox.agent.legacyproxy;

/**
 * Raw HTTP status + body from a single {@link ChatRelay} call — deliberately unparsed so the
 * caller decides how to forward it to its own browser-facing response.
 * <p>
 * Plain class (not a record) — this module's main sources compile to Java 8 bytecode to run
 * inside older integrating applications.
 */
public final class RelayResult {

    private final int statusCode;
    private final String body;

    public RelayResult(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }
}
