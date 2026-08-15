package com.vortox.agent.legacyproxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a configured {@link VortoxChatProxyController} from the environment, so a host does not
 * have to.
 *
 * <p>Resolving the sidecar URL, its key and an optional model is identical work in every host, and
 * every host was writing it — property, then environment variable, then a default. The property and
 * variable names are unchanged from the hand-written versions this replaces, so an existing
 * deployment keeps working without touching its configuration.
 *
 * <p>What remains host-specific is identity: only the application knows who is signed in and which
 * customer they act for. Supply that through {@link HostChatContextEnricher} and a host's entire
 * agent wiring is one expression.
 */
public final class VortoxChatProxy {

    private static final Logger LOG = LoggerFactory.getLogger(VortoxChatProxy.class);

    private static final String DEFAULT_SIDECAR_URL = "http://localhost:7862";

    private VortoxChatProxy() {
    }

    /** A controller pointed at the configured sidecar, enriching every request with {@code enricher}. */
    public static VortoxChatProxyController fromEnvironment(ChatContextEnricher enricher) {
        return new VortoxChatProxyController(sidecarUrl(), sidecarApiKey(),
                enricher == null ? ChatContextEnricher.NOOP : enricher);
    }

    /** {@code -Dagent.sidecar.url}, then {@code AGENT_SIDECAR_URL}, else localhost. */
    public static String sidecarUrl() {
        String url = resolve("agent.sidecar.url", "AGENT_SIDECAR_URL");
        return url == null ? DEFAULT_SIDECAR_URL : url;
    }

    /**
     * The shared key relayed as {@code X-Sidecar-Key}, or null when there is none.
     *
     * <p>Normally absent, and that is not a fault: the sidecar is deployed beside the host on
     * loopback, so the network boundary — not a secret — is what keeps {@code /agent/**} private.
     * Configure one when that stops being true; it must then match the container's own
     * {@code SIDECAR_API_KEY}. Either way it stays on this server-to-server hop and never reaches
     * the page.
     */
    public static String sidecarApiKey() {
        String key = resolve("agent.sidecar.api-key", "SIDECAR_API_KEY");
        if (key == null) {
            LOG.info("No sidecar API key configured — relaying chat without X-Sidecar-Key. Correct "
                    + "while the sidecar is loopback-only; if it answers 401, set -Dagent.sidecar.api-key "
                    + "or SIDECAR_API_KEY to match the container.");
        }
        return key;
    }

    /**
     * An optional model for this deployment, or null to let the agent's own configuration stand.
     *
     * <p>Null is the right default and the reason this helper exists. A host that hardcodes a model
     * silently overrides what Vortox holds for the agent, which is where a model is supposed to be
     * changed — visible only by comparing two log lines, and impossible to fix without a redeploy.
     */
    public static String modelOverride() {
        String model = resolve("agent.model", "AGENT_MODEL");
        if (model != null) {
            LOG.info("Pinning chat model to '{}' for this deployment — this overrides the model "
                    + "configured on the agent in Vortox.", model);
        }
        return model;
    }

    private static String resolve(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(environmentVariable);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
