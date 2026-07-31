package com.vortox.sidecar.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Widget-facing chat request format.
 * Sent by vortox-agent-widget.js on every user message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentChatRequest(
        String message,
        List<Map<String, Object>> history,
        Map<String, Object> context,
        Boolean allowPageScripts,
        String pageApiDescription,
        String systemPrompt,
        String model,
        String llmProvider,        // optional: "anthropic" (default) or "local"
        String llmBaseUrl,         // optional: base URL for local LLM
        String llmApiKey,          // optional: API key/token for the LLM (overrides env default)

        /**
         * The host application's own tenant code, set server-side by its chat proxy — TNAM's
         * enricher already resolves the institution code from the authenticated session.
         *
         * <p>A structured field rather than another entry in {@code context}, because {@code context}
         * is inlined into the prompt as free text and is partly browser-supplied. This value decides
         * which skills and which secrets a run gets, so it must be something the server sets and the
         * page cannot.
         */
        String tenantCode
) {}
