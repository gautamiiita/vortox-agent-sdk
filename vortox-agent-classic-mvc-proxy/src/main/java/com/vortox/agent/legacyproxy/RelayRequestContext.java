package com.vortox.agent.legacyproxy;

import java.util.Collections;
import java.util.Map;

/**
 * Framework-neutral stand-in for "the inbound browser request" — populated by
 * {@link VortoxChatProxyController} from the real {@code javax.servlet.http.HttpServletRequest},
 * so {@link ChatContextEnricher} implementations never need a servlet dependency themselves.
 */
public final class RelayRequestContext {

    private final Map<String, String> headers;
    private final String remoteUser;
    private final String remoteAddr;

    public RelayRequestContext(Map<String, String> headers, String remoteUser, String remoteAddr) {
        this.headers = headers != null ? headers : Collections.<String, String>emptyMap();
        this.remoteUser = remoteUser;
        this.remoteAddr = remoteAddr;
    }

    public static RelayRequestContext empty() {
        return new RelayRequestContext(Collections.<String, String>emptyMap(), null, null);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getRemoteUser() {
        return remoteUser;
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }
}
