package com.vortox.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * Human control channel into a running {@link com.vortox.agent.ReactLoop}.
 *
 * <p>The loop calls {@link #beforeIteration} once at the top of every iteration, before the
 * next LLM call. This single seam lets an operator steer a live run:</p>
 * <ul>
 *   <li><b>Pause</b> — the implementation may block inside this call until the run is resumed
 *       (or cancelled); the loop simply waits.</li>
 *   <li><b>Guide</b> — the implementation may append human messages to the mutable
 *       {@code messages} list (e.g. {@code {"role":"user","content":"[Human guidance] …"}}),
 *       which the loop then sends on the next LLM call.</li>
 *   <li><b>Cancel</b> — returning {@code false} asks the loop to stop cleanly and return a
 *       {@link com.vortox.agent.AgentResult#cancelled} result (work + history preserved).</li>
 * </ul>
 *
 * <p>Intervention takes effect at iteration boundaries — never mid-LLM-call or mid-tool. This is
 * an executor-agnostic contract: both the in-process (backend) and sidecar hosts implement it.</p>
 */
public interface ControlHook {

    /**
     * @param runId    the run/task identifier
     * @param iteration the iteration about to execute (1-based)
     * @param messages  the live, mutable conversation — append guidance here to inject it
     * @return {@code true} to proceed with this iteration, {@code false} to cancel the run
     */
    boolean beforeIteration(String runId, int iteration, List<Map<String, Object>> messages);

    /** No-op hook — the default when no control channel is wired. */
    ControlHook NOOP = (runId, iteration, messages) -> true;
}
