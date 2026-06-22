package com.vortox.sidecar.api;

import java.util.List;

public record AgentRunResponse(
        String runId,
        String status,
        String result,
        List<ToolCallDto> toolCalls,
        int inputTokens,
        int outputTokens
) {
    public record ToolCallDto(String toolName, boolean success, long durationMs) {}
}
