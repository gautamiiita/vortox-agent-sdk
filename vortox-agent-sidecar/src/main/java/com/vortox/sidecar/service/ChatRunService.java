package com.vortox.sidecar.service;

import com.vortox.agent.spi.ActivityListener;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs /agent/chat requests off the request thread so the widget can poll for completion
 * instead of holding one HTTP connection open for the whole ReAct loop. Some runs legitimately
 * take many minutes — several LLM iterations plus tool calls, some skills configured up to
 * 30 minutes (e.g. expo_eas_build) — far past any HTTP client's sane timeout.
 * <p>
 * State is in-memory and ephemeral, same as everything else in this container: lost on
 * restart, not shared across replicas. That's fine here because the widget always polls
 * the same origin it started the run on within one browser session.
 */
@Service
public class ChatRunService {

    private static final Logger log = LoggerFactory.getLogger(ChatRunService.class);

    /** How long a finished (DONE/ERROR) result is kept around for a straggling poll. */
    private static final long TTL_DONE_MS = 30 * 60 * 1000L;
    /** Safety net for a RUNNING entry that never got a completion callback (e.g. a bug). */
    private static final long TTL_RUNNING_MS = 2 * 60 * 60 * 1000L;

    /**
     * APPROVAL_NEEDED: the run stopped on a tool that waits for a person; {@code approval} says what
     * is asked, and {@link #decide} continues it.
     */
    public enum Status { RUNNING, APPROVAL_NEEDED, DONE, ERROR }

    /** What {@link #decide} did. */
    public enum Decision { ACCEPTED, NOT_FOUND, NOT_WAITING }

    private record RunState(Status status, String reply, List<AgentRunResponse.ArtifactDto> artifacts,
                             String error, long updatedAtMs, String approval) {
        RunState(Status status, String reply, List<AgentRunResponse.ArtifactDto> artifacts,
                 String error, long updatedAtMs) {
            this(status, reply, artifacts, error, updatedAtMs, null);
        }
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "chat-run-worker");
        t.setDaemon(true);
        return t;
    });

    private final AgentService agentService;
    private final AgentStreamRegistry streamRegistry;

    public ChatRunService(AgentService agentService, AgentStreamRegistry streamRegistry) {
        this.agentService   = agentService;
        this.streamRegistry = streamRegistry;
    }

    /** Starts the run in the background and returns immediately with a runId to poll. */
    public String start(AgentRunRequest runRequest) {
        String runId = UUID.randomUUID().toString();
        runs.put(runId, new RunState(Status.RUNNING, null, null, null, System.currentTimeMillis()));

        // Must be created before the run starts so no early progress event is dropped —
        // the queue buffers events regardless of whether a stream consumer has connected yet.
        ActivityListener listener = streamRegistry.listenerFor(runId);

        executor.submit(() -> settle(runId, () -> agentService.run(runRequest, runId, listener)));

        return runId;
    }

    /**
     * Answers a run waiting for approval and resumes it in the background: approved runs the held
     * call, rejected tells the agent it may not. Exactly one decision is taken per wait — a second
     * click, or a decision for a run that is not waiting, changes nothing.
     *
     * @param apiKey the person's model key, brought again: a waiting run does not keep it
     */
    public Decision decide(String runId, boolean approved, String note, String apiKey) {
        RunState current = runId == null ? null : runs.get(runId);
        if (current == null) return Decision.NOT_FOUND;
        if (current.status() != Status.APPROVAL_NEEDED) return Decision.NOT_WAITING;
        RunState resumed = new RunState(Status.RUNNING, null, null, null, System.currentTimeMillis());
        // Compare-and-set on the exact state read: two decisions racing cannot both resume the run.
        if (!runs.replace(runId, current, resumed)) return Decision.NOT_WAITING;

        ActivityListener listener = streamRegistry.listenerFor(runId);
        executor.submit(() -> settle(runId, () -> agentService.resume(runId, approved, note, apiKey, listener)));
        log.info("Chat run {} {} by the user — resuming", runId, approved ? "approved" : "rejected");
        return Decision.ACCEPTED;
    }

    /**
     * Runs one stretch of the agent and records where it ended: waiting for a person, done, or
     * failed. FAILED (refused before the loop — no key, an expired approval) and ERROR (the loop
     * failed) are errors, not replies, so the widget can react instead of printing the failure as if
     * the agent had said it.
     */
    private void settle(String runId, java.util.function.Supplier<AgentRunResponse> stretch) {
        try {
            AgentRunResponse response = stretch.get();
            long now = System.currentTimeMillis();
            if ("APPROVAL_NEEDED".equals(response.status())) {
                runs.put(runId, new RunState(Status.APPROVAL_NEEDED, null, null, null, now, response.result()));
                log.info("Chat run {} waiting for approval", runId);
                return;
            }
            if ("FAILED".equals(response.status()) || "ERROR".equals(response.status())) {
                String why = response.result() != null && !response.result().isBlank()
                        ? response.result() : "The agent run failed.";
                runs.put(runId, new RunState(Status.ERROR, null, null, why, now));
                log.info("Chat run {} failed: {}", runId, why);
                return;
            }
            String reply = (response.result() != null && !response.result().isBlank())
                    ? response.result()
                    : "I couldn't generate a response. Please try again.";
            List<AgentRunResponse.ArtifactDto> artifacts =
                    response.artifacts() != null ? response.artifacts() : List.of();

            runs.put(runId, new RunState(Status.DONE, reply, artifacts, null, now));
            log.info("Chat run {} completed — status={} inputTokens={} outputTokens={} artifacts={}",
                    runId, response.status(), response.inputTokens(), response.outputTokens(), artifacts.size());
        } catch (Exception e) {
            log.error("Chat run {} failed: {}", runId, e.getMessage(), e);
            runs.put(runId, new RunState(Status.ERROR, null, null,
                    "Internal error: " + e.getMessage(), System.currentTimeMillis()));
        } finally {
            // Always releases a blocked stream reader, including when the agent throws before
            // ReactLoop ever reaches a callback that would signal completion.
            streamRegistry.complete(runId);
        }
    }

    /** Returns the current status/result payload for a run, or empty if unknown/expired. */
    public Optional<Map<String, Object>> status(String runId) {
        RunState s = runs.get(runId);
        if (s == null) return Optional.empty();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", s.status().name());
        if (s.reply() != null)     body.put("reply", s.reply());
        if (s.artifacts() != null) body.put("artifacts", s.artifacts());
        if (s.error() != null)     body.put("error", s.error());
        if (s.approval() != null)  body.put("approval", s.approval());
        return Optional.of(body);
    }

    /** Bounds memory for runs whose result is never polled, and clears any orphaned entries. */
    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    public void evictStale() {
        long now = System.currentTimeMillis();
        int before = runs.size();
        runs.values().removeIf(s -> {
            long ttl = s.status() == Status.DONE || s.status() == Status.ERROR ? TTL_DONE_MS : TTL_RUNNING_MS;
            return now - s.updatedAtMs() > ttl;
        });
        int removed = before - runs.size();
        if (removed > 0) log.debug("ChatRunService: evicted {} stale run(s)", removed);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
