package com.vortox.agent.spi;

import java.util.Map;

/**
 * Executes a named tool and returns its result as a string (typically JSON).
 *
 * The library drives the ReAct conversation loop; callers supply the actual
 * tool implementations through this interface.  A result of {@code null} is
 * treated as an empty success response.
 *
 * <p>Built-in tools ({@code task_complete}, {@code request_clarification},
 * {@code request_approval}, {@code request_handoff}, {@code execute_command},
 * and the memory tools) are handled internally by {@link com.vortox.agent.ReactLoop}
 * and never forwarded here.</p>
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * @param toolName the exact tool name from the LLM's {@code tool_use} block
     * @param params   the {@code input} map from the same block
     * @param runId    caller-supplied correlation ID for the current agent run
     * @return tool result as a string; JSON is preferred so the LLM can parse structure
     * @throws ToolExecutionException when the tool fails and the error should be fed back to the LLM
     */
    String execute(String toolName, Map<String, Object> params, String runId);

    /** Checked exception that carries a user-visible error message back to the LLM. */
    class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String message) { super(message); }
        public ToolExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
