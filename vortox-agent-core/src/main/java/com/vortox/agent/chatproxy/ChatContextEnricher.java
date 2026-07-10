package com.vortox.agent.chatproxy;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lets an integrating application inject its own auth/institution context into an outgoing
 * chat payload before it's relayed to the sidecar, and/or strip or override fields the app
 * doesn't want a client to control (e.g. {@code llmProvider}, {@code model}). The SDK never
 * hardcodes any app-specific field — this is the one extension point for that.
 */
public interface ChatContextEnricher {

    void enrich(ObjectNode payload, RelayRequestContext context);

    /** Default when an integrator hasn't configured one — forwards the payload unchanged. */
    ChatContextEnricher NOOP = (payload, context) -> { };
}
