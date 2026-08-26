package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lets an integrating application inject its own auth/institution context into an outgoing
 * chat payload before it's relayed to the sidecar, and/or strip or override fields the app
 * doesn't want a client to control (e.g. {@code model}). The SDK never hardcodes any
 * app-specific field — this is the one extension point for that.
 *
 * <p><b>Not the place for security-critical stripping.</b> An enricher is optional, and {@link #NOOP}
 * forwards everything, so anything that <em>must</em> be removed cannot depend on one being
 * installed. {@code llmProvider}, {@code llmBaseUrl}, {@code llmApiKey} and {@code systemPrompt} are
 * therefore removed by the relay itself before this runs — they decide where the conversation is
 * sent and who the agent is. An enricher may still set them deliberately; it just can't be the only
 * thing standing between the browser and them.
 */
public interface ChatContextEnricher {

    void enrich(ObjectNode payload, RelayRequestContext context);

    /** Default when an integrator hasn't configured one — forwards the payload unchanged. */
    ChatContextEnricher NOOP = new ChatContextEnricher() {
        @Override
        public void enrich(ObjectNode payload, RelayRequestContext context) {
            // no-op
        }
    };
}
