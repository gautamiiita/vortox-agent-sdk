package com.vortox.agent;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a {@link ReactLoop} run.
 *
 * Inspect {@link #status()} first, then read the relevant fields:
 * <ul>
 *   <li>{@code SUCCESS} / {@code PARTIAL} — read {@link #response()}</li>
 *   <li>{@code CLARIFICATION_NEEDED} — read {@link #clarificationQuestion()}</li>
 *   <li>{@code APPROVAL_NEEDED}       — read {@link #approvalRequest()}, store the snapshot for resume</li>
 *   <li>{@code HANDOFF}               — read {@link #handoffTargetRole()} and route to the next agent</li>
 *   <li>{@code ERROR}                 — read {@link #error()}</li>
 * </ul>
 */
public record AgentResult(
        Status status,
        String response,
        String error,

        // Clarification gate
        String clarificationQuestion,

        // Approval gate
        String approvalRequest,
        String approvalToolUseId,
        List<Map<String, Object>> approvalAssistantContent,

        // Handoff
        String handoffTargetRole,
        String handoffReason,
        String handoffContext,

        // Execution stats
        int iterations,
        List<ToolCall> toolCalls,

        // Token usage
        int inputTokens,
        int outputTokens,
        int cacheCreationTokens,
        int cacheReadTokens,

        // Full conversation — preserved for continuation / approval resume
        List<Map<String, Object>> conversationHistory
) {

    public enum Status {
        SUCCESS, PARTIAL, CLARIFICATION_NEEDED, APPROVAL_NEEDED, HANDOFF, ERROR
    }

    /** Immutable record of one tool invocation. */
    public record ToolCall(String toolName, Map<String, Object> input, boolean success, long durationMs) {}

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static AgentResult success(String response, int iterations,
                                       List<ToolCall> toolCalls,
                                       List<Map<String, Object>> history,
                                       int in, int out, int cacheCreate, int cacheRead) {
        return new AgentResult(Status.SUCCESS, response, null, null, null, null, null,
                null, null, null, iterations, toolCalls, in, out, cacheCreate, cacheRead, history);
    }

    public static AgentResult partial(String response, int iterations,
                                       List<ToolCall> toolCalls,
                                       List<Map<String, Object>> history,
                                       int in, int out, int cacheCreate, int cacheRead) {
        return new AgentResult(Status.PARTIAL, response, null, null, null, null, null,
                null, null, null, iterations, toolCalls, in, out, cacheCreate, cacheRead, history);
    }

    public static AgentResult needsClarification(String question,
                                                   int in, int out, int cacheCreate, int cacheRead) {
        return new AgentResult(Status.CLARIFICATION_NEEDED, null, null, question, null, null, null,
                null, null, null, 0, List.of(), in, out, cacheCreate, cacheRead, List.of());
    }

    public static AgentResult needsApproval(String request, String toolUseId,
                                             List<Map<String, Object>> assistantContent,
                                             List<Map<String, Object>> snapshot,
                                             int in, int out, int cacheCreate, int cacheRead) {
        return new AgentResult(Status.APPROVAL_NEEDED, null, null, null,
                request, toolUseId, assistantContent,
                null, null, null, 0, List.of(), in, out, cacheCreate, cacheRead, snapshot);
    }

    public static AgentResult handoff(String targetRole, String reason, String context,
                                       String summary, int iterations,
                                       List<ToolCall> toolCalls,
                                       List<Map<String, Object>> history,
                                       int in, int out, int cacheCreate, int cacheRead) {
        return new AgentResult(Status.HANDOFF, summary, null, null, null, null, null,
                targetRole, reason, context, iterations, toolCalls, in, out, cacheCreate, cacheRead, history);
    }

    public static AgentResult error(String error, int in, int out) {
        return new AgentResult(Status.ERROR, null, error, null, null, null, null,
                null, null, null, 0, List.of(), in, out, 0, 0, List.of());
    }

    public static AgentResult error(String error) { return error(error, 0, 0); }

    // ── Convenience predicates ────────────────────────────────────────────────

    public boolean isSuccess()             { return status == Status.SUCCESS; }
    public boolean isPartial()             { return status == Status.PARTIAL; }
    public boolean isError()               { return status == Status.ERROR; }
    public boolean needsClarification()    { return status == Status.CLARIFICATION_NEEDED; }
    public boolean needsApproval()         { return status == Status.APPROVAL_NEEDED; }
    public boolean isHandoff()             { return status == Status.HANDOFF; }
}
