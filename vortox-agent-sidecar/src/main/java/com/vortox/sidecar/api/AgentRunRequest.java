package com.vortox.sidecar.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunRequest(
        String task,
        List<String> skills,       // null or empty = all loaded skills
        String systemPrompt,       // optional override
        String model,              // optional, defaults to configured default
        Integer maxIterations,     // optional, defaults to 100
        String apiKey,             // optional, falls back to env var
        String llmProvider,        // optional: "anthropic" (default) or "local"
        String llmBaseUrl          // optional: base URL for local LLM (e.g. https://ai.svc.elca.ch)
) {}
