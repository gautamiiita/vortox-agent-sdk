package com.vortox.sidecar.api;

import java.util.List;

public record AgentRunResponse(
        String runId,
        String status,
        String result,
        List<ToolCallDto> toolCalls,
        int inputTokens,
        int outputTokens,
        List<ArtifactDto> artifacts
) {
    public record ToolCallDto(String toolName, boolean success, long durationMs) {}

    /**
     * A file produced by a tool call (e.g. file_write) during the run, inlined as base64
     * so the caller (widget, dashboard) can offer it as a download without any server-side
     * storage — the sidecar's local filesystem is ephemeral and not otherwise reachable.
     */
    public record ArtifactDto(String filename, String mimeType, long sizeBytes, String base64) {}
}
