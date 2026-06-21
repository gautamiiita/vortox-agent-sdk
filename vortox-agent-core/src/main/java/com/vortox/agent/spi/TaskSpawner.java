package com.vortox.agent.spi;

import java.util.Map;

/**
 * Backs the built-in {@code spawn_task} tool.
 *
 * Implement this to let an agent create new tasks and assign them to other agents.
 * When not configured, calling {@code spawn_task} returns an error message to the LLM.
 */
@FunctionalInterface
public interface TaskSpawner {

    /**
     * Create a new task and return a JSON summary string describing the outcome,
     * e.g. {@code {"success":true,"taskId":"abc-123","status":"ASSIGNED"}}.
     *
     * @param params        the raw {@code input} map from the LLM's {@code spawn_task} call
     * @param callerRunId   runId of the agent that issued the spawn
     * @return              JSON result string fed back to the LLM as the tool result
     */
    String spawn(Map<String, Object> params, String callerRunId);
}
