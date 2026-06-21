package com.vortox.agent.spi;

import com.vortox.agent.AgentResult;

import java.util.Map;

/**
 * Observability hook for the ReAct loop.
 *
 * All methods have empty default implementations so callers only override
 * what they need.  The listener is called synchronously inside the loop;
 * implementations must not block for long or throw unchecked exceptions.
 */
public interface ActivityListener {

    /** Called at the start of each LLM iteration. */
    default void onIteration(String runId, int iteration, int maxIterations, String description) {}

    /** Called just before a tool is dispatched to the {@link ToolExecutor}. */
    default void onToolCall(String runId, String toolName, Map<String, Object> params) {}

    /** Called after a tool returns (success or failure). */
    default void onToolResult(String runId, String toolName, boolean success, long durationMs) {}

    /** Called when the loop ends — success, partial, clarification, approval, or error. */
    default void onComplete(String runId, AgentResult result) {}

    /** Called for any notable error inside the loop (LLM failure, tool timeout, …). */
    default void onError(String runId, String description) {}

    /** No-op listener — used as the default when none is configured. */
    ActivityListener NOOP = new ActivityListener() {};
}
