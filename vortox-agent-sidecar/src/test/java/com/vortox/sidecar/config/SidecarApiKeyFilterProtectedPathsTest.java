package com.vortox.sidecar.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which paths the shared-key filter guards.
 *
 * <p>This was a bare {@code getRequestURI().startsWith("/agent")}, which had two problems. It left
 * {@code POST /vortox/skills/reload} — the control-plane reload hook — open to anyone who could
 * reach the port, on a service whose own documentation calls this filter the perimeter. And a
 * prefix test on a raw URI is only as good as the container's normalisation: {@code "//agent/run"}
 * does not start with {@code "/agent"}, so whether that bypassed the filter came down to whether
 * Tomcat happened to collapse the slashes before Spring routed it.
 */
class SidecarApiKeyFilterProtectedPathsTest {

    @Test
    void guardsTheAgentRoutes() {
        assertThat(SidecarApiKeyFilter.isProtected("/agent/run")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("/agent/skills/upload")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("/agent/chat/abc/stream")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("/agent")).isTrue();
    }

    /** The gap this fix closes: reachable unauthenticated, and it spawned a thread per call. */
    @Test
    void guardsTheVortoxControlPlaneWebhook() {
        assertThat(SidecarApiKeyFilter.isProtected("/vortox/skills/reload")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("/vortox")).isTrue();
    }

    @Test
    void leavesHealthChecksAndStaticAssetsAlone() {
        assertThat(SidecarApiKeyFilter.isProtected("/actuator/health")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("/vortox-agent-widget.js")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("/demo.html")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("/")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected(null)).isFalse();
    }

    /**
     * A duplicated leading slash must not decide whether the key is required. Segment matching also
     * means a route that merely starts with the same letters isn't guarded by accident.
     */
    @Test
    void isNotFooledByALeadingDoubleSlash() {
        assertThat(SidecarApiKeyFilter.isProtected("//agent/run")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("///agent/run")).isTrue();
        assertThat(SidecarApiKeyFilter.isProtected("//vortox/skills/reload")).isTrue();
    }

    @Test
    void matchesWholeSegmentsNotBarePrefixes() {
        assertThat(SidecarApiKeyFilter.isProtected("/agentless/thing")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("/agents")).isFalse();
        assertThat(SidecarApiKeyFilter.isProtected("/vortoxian")).isFalse();
    }
}
