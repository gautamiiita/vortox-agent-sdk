package com.vortox.agent.starter;

import java.util.List;

/**
 * The outcome of an agent run. Always inspect {@link #isSuccess()} before reading {@link #getResult()}.
 */
public final class AgentResponse {

    private final String runId;
    private final String status;
    private final String result;
    private final List<ToolCall> toolCalls;
    private final int inputTokens;
    private final int outputTokens;

    AgentResponse(String runId, String status, String result,
                  List<ToolCall> toolCalls, int inputTokens, int outputTokens) {
        this.runId = runId;
        this.status = status;
        this.result = result;
        this.toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    /** True when the sidecar returned status SUCCESS or PARTIAL. */
    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status) || "PARTIAL".equalsIgnoreCase(status);
    }

    public String         getRunId()       { return runId; }
    public String         getStatus()      { return status; }
    /** The agent's final answer. */
    public String         getResult()      { return result; }
    public List<ToolCall> getToolCalls()   { return toolCalls; }
    public int            getInputTokens() { return inputTokens; }
    public int            getOutputTokens(){ return outputTokens; }

    /** Mirrors the sidecar's AgentRunResponse.ToolCallDto. */
    public record ToolCall(String toolName, boolean success, long durationMs) {}

    @Override
    public String toString() {
        return "AgentResponse{status='" + status + "', result='" + result + "', iterations=" +
                toolCalls.size() + " tool calls}";
    }
}
