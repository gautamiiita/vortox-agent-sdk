package com.vortox.agent.chatproxy;

import java.util.Map;

/**
 * Framework-neutral stand-in for "the inbound browser request" — populated by each web
 * adapter (jakarta or javax servlet) from its own real request type, so {@link ChatContextEnricher}
 * implementations never need a servlet dependency at all.
 */
public record RelayRequestContext(Map<String, String> headers, String remoteUser, String remoteAddr) {

    public static RelayRequestContext empty() {
        return new RelayRequestContext(Map.of(), null, null);
    }
}
