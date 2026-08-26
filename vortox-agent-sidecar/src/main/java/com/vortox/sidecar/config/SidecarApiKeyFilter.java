package com.vortox.sidecar.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires a shared API key on every request under a protected prefix.
 *
 * <p>Without this the sidecar is unauthenticated remote code execution: {@code POST
 * /agent/skills/upload} writes an executable script and {@code POST /agent/run} runs it. Nothing
 * else guards these routes — the sidecar has no Spring Security on the classpath.</p>
 *
 * <p><b>Protected prefixes are an allow-list, not a single {@code startsWith}.</b> This used to
 * guard only {@code /agent}, which left {@code POST /vortox/skills/reload} — the control-plane
 * reload hook — reachable by anyone who could open a socket. That endpoint discloses nothing on its
 * own (the sync it triggers authenticates itself), but it spawned a thread per call, so the gap was
 * a denial-of-service and a way to drive an unbounded set of tenant codes into the poll loop. Adding
 * a route to this service should not silently add an unauthenticated route, so new prefixes are
 * listed here deliberately.</p>
 *
 * <p><b>Fails closed.</b> If {@code sidecar.api-key} is unset, every {@code /agent/**} request is
 * refused rather than allowed. An unconfigured sidecar that rejects work is a visible outage; an
 * unconfigured sidecar that accepts anonymous work is a breach nobody notices.</p>
 *
 * <p><b>Can be switched off deliberately.</b> Set {@code sidecar.auth-enabled=false} for a true
 * sidecar deployment — one reachable only from its own host application, over loopback or a private
 * network namespace, never on a published address. There the network boundary is the control and
 * this key is redundant. It is a separate flag rather than "an unset key means open" so that the
 * dangerous state can only be reached on purpose, and never by a missing environment variable.</p>
 *
 * <p>{@code /actuator/health} stays open so container health checks and load balancers work; it
 * exposes no state worth authenticating.</p>
 */
@Component
public class SidecarApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SidecarApiKeyFilter.class);

    private static final String HEADER = "X-Sidecar-Key";

    /**
     * Path prefixes that require the key. {@code /actuator/health} deliberately stays outside so
     * container health checks and load balancers work; it exposes no state worth authenticating.
     */
    private static final java.util.List<String> PROTECTED_PREFIXES =
            java.util.List.of("/agent", "/vortox");

    private final byte[] expectedKey;
    private final boolean configured;
    private final boolean authEnabled;

    public SidecarApiKeyFilter(@Value("${sidecar.api-key:}") String apiKey,
                               @Value("${sidecar.auth-enabled:true}") boolean authEnabled) {
        this.authEnabled = authEnabled;
        this.configured  = apiKey != null && !apiKey.isBlank();
        this.expectedKey = configured ? apiKey.trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!authEnabled) {
            // Warned about at every startup, not just once at the moment someone sets it: /agent/run
            // executes uploaded scripts, so whoever inherits this deployment needs to know from the
            // logs alone that the only thing standing between that and the network is the binding.
            log.warn("sidecar.auth-enabled=false — {} are UNAUTHENTICATED. Anything that can "
                    + "reach this port can upload and execute a skill script. Only safe when the "
                    + "port is bound to loopback or a private network the host application shares.",
                    PROTECTED_PREFIXES);
        } else if (!configured) {
            log.error("sidecar.api-key is not set — every request under {} will be refused. "
                    + "Set SIDECAR_API_KEY to enable the sidecar.", PROTECTED_PREFIXES);
        }
    }

    /** Only the protected prefixes are guarded; health checks and static assets are not. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !authEnabled || !isProtected(request.getRequestURI());
    }

    /**
     * True when {@code uri} falls under a protected prefix.
     *
     * <p>Matches on whole path segments rather than a bare {@code startsWith}, so a route named
     * {@code /agentless-something} isn't guarded by accident and — more to the point — a request
     * that Spring routes to a protected handler cannot present a URI that slips past the check.
     * Leading empty segments are collapsed first: Tomcat hands {@code //agent/run} through as-is,
     * and {@code "//agent/run".startsWith("/agent")} is false, so the naive form left a bypass
     * dangling on whether the servlet container happened to normalise it.
     */
    /* package-private for testability */
    static boolean isProtected(String uri) {
        if (uri == null || uri.isEmpty()) return false;
        String path = uri;
        while (path.startsWith("//")) path = path.substring(1);
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!configured) {
            deny(response, "Sidecar is not configured with an API key");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || presented.isBlank()) {
            deny(response, "Missing " + HEADER);
            return;
        }

        // Constant-time compare so a wrong key can't be recovered by timing the response.
        if (!MessageDigest.isEqual(presented.trim().getBytes(StandardCharsets.UTF_8), expectedKey)) {
            log.warn("Rejected {} {} — bad sidecar key from {}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            deny(response, "Invalid " + HEADER);
            return;
        }

        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
