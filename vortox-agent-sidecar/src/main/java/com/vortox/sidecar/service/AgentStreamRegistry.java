package com.vortox.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.AgentResult;
import com.vortox.agent.spi.ActivityListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * In-memory per-run event queue that lets a {@code GET .../stream} SSE endpoint show live
 * ReAct-loop progress (iteration count, tool calls) to a caller, mirroring the Vortox backend's
 * {@code PlannerStreamRegistry} pattern. State is ephemeral — lost on restart, not shared across
 * replicas — same as {@link ChatRunService}'s own run map.
 * <p>
 * The terminal {@code done} signal is deliberately empty: the SSE consumer is expected to make
 * one follow-up call to the existing poll endpoint for the authoritative {@code {reply, artifacts}}
 * payload, rather than duplicating that (potentially large, base64-artifact-bearing) result
 * through this channel too.
 */
@Component
public class AgentStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamRegistry.class);

    /** Safety net for a queue whose stream endpoint never connects (browser never opened it). */
    private static final long TTL_MS = 10 * 60 * 1000L;

    public record Evt(String name, String json) {}

    private static final Evt SENTINEL = new Evt("__done__", null);

    private final ConcurrentHashMap<String, LinkedBlockingQueue<Evt>> queues     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>                     timestamps = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Lazily creates the queue for {@code runId} (if not already present) and returns an
     * {@link ActivityListener} that publishes onto it. Must be called before the run starts,
     * so early events aren't dropped before a consumer connects — the queue buffers them
     * regardless (capacity 2000, same as the backend's PlannerStreamRegistry).
     */
    public ActivityListener listenerFor(String runId) {
        queues.computeIfAbsent(runId, k -> new LinkedBlockingQueue<>(2000));
        timestamps.put(runId, System.currentTimeMillis());

        return new ActivityListener() {
            @Override
            public void onIteration(String rid, int iteration, int maxIterations, String description) {
                publish(rid, "iteration", Map.of("iteration", iteration, "maxIterations", maxIterations));
            }

            @Override
            public void onToolCall(String rid, String toolName, Map<String, Object> params) {
                publish(rid, "tool_call", Map.of("tool", toolName));
            }

            @Override
            public void onToolResult(String rid, String toolName, boolean success, long durationMs) {
                publish(rid, "tool_result", Map.of("tool", toolName, "success", success, "durationMs", durationMs));
            }

            @Override
            public void onError(String rid, String description) {
                publish(rid, "error", Map.of("message", description));
            }

            @Override
            public void onComplete(String rid, AgentResult result) {
                // No-op: completion is signalled by ChatRunService calling complete(runId) in a
                // finally block, so a stream reader is released even if the run throws before
                // ReactLoop ever reaches a return path that would call this callback.
            }
        };
    }

    private void publish(String runId, String name, Map<String, Object> data) {
        var q = queues.get(runId);
        if (q == null) return;
        try {
            q.offer(new Evt(name, objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            log.warn("AgentStreamRegistry: failed to serialize event '{}' for run {}: {}", name, runId, e.getMessage());
        }
    }

    /** Signal end-of-stream. The SSE endpoint's drain loop calls this the sentinel and closes. */
    public void complete(String runId) {
        var q = queues.get(runId);
        if (q != null) q.offer(SENTINEL);
    }

    /**
     * Block for up to {@code timeoutMs} waiting for the next event.
     * Returns {@code null} on timeout; returns the sentinel when the stream is done.
     */
    public Evt poll(String runId, long timeoutMs) throws InterruptedException {
        var q = queues.get(runId);
        if (q == null) return SENTINEL;
        return q.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isDone(Evt evt) {
        return evt == SENTINEL || (evt != null && "__done__".equals(evt.name()));
    }

    public boolean exists(String runId) {
        return queues.containsKey(runId);
    }

    public void remove(String runId) {
        queues.remove(runId);
        timestamps.remove(runId);
    }

    /** Evicts queues whose stream endpoint was never connected to (or never completed) within TTL_MS. */
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        timestamps.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                queues.remove(e.getKey());
                log.debug("AgentStreamRegistry: evicted stale stream for run {}", e.getKey());
                return true;
            }
            return false;
        });
    }
}
