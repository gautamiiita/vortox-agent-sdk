package com.vortox.sidecar.service;

import com.vortox.sidecar.api.AgentRunRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs paused on a tool that waits for a person, keyed by runId — what {@link AgentService#resume}
 * needs to continue exactly where the run stopped.
 *
 * <p>In memory, like every other piece of run state in this container: lost on restart and not shared
 * across replicas, which is fine for a person answering within the same session. Bounded in time —
 * a decision arriving after {@code ttlMs} finds the run expired — and taken at most once, so two
 * clicks cannot run an approved call twice.
 *
 * <p>The request is kept WITHOUT its API key: a person's model key is held while their run is
 * working, not while it sits waiting for them. The decision brings it again.
 */
public final class PendingApprovals {

    /** Everything needed to resume. {@code request} has had its apiKey removed. */
    public record Pending(AgentRunRequest request, String approvalText,
                          List<Map<String, Object>> snapshot,
                          List<Map<String, Object>> assistantContent,
                          String toolUseId, long createdAtMs) {}

    /** What {@link #take} found. */
    public sealed interface Taken permits Found, Missing, Expired {}
    public record Found(Pending pending) implements Taken {}
    public record Missing() implements Taken {}
    public record Expired() implements Taken {}

    private final Map<String, Pending> byRun = new ConcurrentHashMap<>();
    private final long ttlMs;

    public PendingApprovals(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public void put(String runId, Pending pending) {
        byRun.put(runId, pending);
    }

    /** Removes and returns the run's pending approval: used at most once. */
    public Taken take(String runId) {
        Pending p = runId == null ? null : byRun.remove(runId);
        if (p == null) return new Missing();
        if (System.currentTimeMillis() - p.createdAtMs() > ttlMs) return new Expired();
        return new Found(p);
    }

    /** Looks without taking — for tests and diagnostics. */
    public Optional<Pending> peek(String runId) {
        return Optional.ofNullable(byRun.get(runId));
    }

    /** Drops entries nobody decided in time. */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        byRun.values().removeIf(p -> now - p.createdAtMs() > ttlMs);
    }

    /** Ages every entry past the limit — for tests. */
    void expireAll() {
        byRun.replaceAll((k, p) -> new Pending(p.request(), p.approvalText(), p.snapshot(),
                p.assistantContent(), p.toolUseId(), p.createdAtMs() - ttlMs - 1));
    }
}
