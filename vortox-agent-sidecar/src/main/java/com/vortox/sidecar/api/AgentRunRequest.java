package com.vortox.sidecar.api;

import java.util.List;

public record AgentRunRequest(
        String task,
        List<String> skills,       // null or empty = all loaded skills
        String systemPrompt,       // optional override
        String model,              // optional, defaults to claude-sonnet-4-6
        Integer maxIterations,     // optional, defaults to 100
        String apiKey              // optional, falls back to ANTHROPIC_API_KEY env var
) {}
