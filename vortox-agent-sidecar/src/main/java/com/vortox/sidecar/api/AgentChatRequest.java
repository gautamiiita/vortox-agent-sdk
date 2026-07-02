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
        String llmApiKey           // optional: API key/token for the LLM (overrides env default)
) {}
