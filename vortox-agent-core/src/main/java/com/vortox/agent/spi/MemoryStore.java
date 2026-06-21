package com.vortox.agent.spi;

import java.util.List;

/**
 * Persistence backend for the agent's built-in memory tools
 * ({@code remember_memory}, {@code recall_memory}, {@code write_task_memory},
 * {@code read_task_memory}).
 *
 * <p>The default implementation ({@link com.vortox.agent.spi.InMemoryStore}) stores
 * memories in a {@code ConcurrentHashMap} for the lifetime of the JVM process.
 * Replace it with a database-backed implementation to persist memories across restarts.</p>
 */
public interface MemoryStore {

    /**
     * Store or update a memory entry.
     *
     * @param scope      {@code "agent"} for cross-run agent memory, {@code "task"} for task-scoped context
     * @param scopeId    agentId when scope=agent, runId/taskId when scope=task
     * @param key        snake_case identifier (unique within scope+scopeId)
     * @param content    the text to remember
     * @param type       one of CONTEXT | PATTERN | DECISION | OUTCOME
     * @param importance 1–5 (5 = critical)
     */
    void store(String scope, String scopeId, String key, String content, String type, int importance);

    /**
     * Search for memories relevant to {@code query} for the given agent.
     * A simple implementation may return all memories; a smarter one may rank by relevance.
     */
    List<MemoryEntry> recallForAgent(String agentId, String query);

    /**
     * Return all memories written for a specific run/task (task-scoped context).
     */
    List<MemoryEntry> loadForRun(String runId);

    /** Immutable memory record returned by recall operations. */
    record MemoryEntry(String key, String content, String type, int importance) {}
}
