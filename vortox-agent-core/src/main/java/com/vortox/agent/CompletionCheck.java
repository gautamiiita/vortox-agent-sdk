package com.vortox.agent;

import java.util.function.Supplier;

/**
 * A check of finished work made with a fresh context, before a run's success is accepted.
 *
 * <p><strong>Why a fresh context.</strong> An agent that checks its own work at the end of a long run
 * checks it with everything it reasoned on the way: a decision taken at step 20 ("these two services
 * are out of scope") is still in the conversation at step 51, and the self-check repeats it. Benchmark
 * m1 (2026-09-30) is that exactly — the developer narrowed "replace every query" to the files it had
 * changed and then reported the requirement MET. A separate call that sees only what was asked and
 * what was done cannot reuse a justification it never saw.
 *
 * <p>So the check is given the {@link #requirements()} and the {@link #evidence()} — gathered by the
 * host at the moment of completion, from the work itself (for code, the change and the project as it
 * is now), never from the agent's own account. A requirement it finds not met goes back into the run
 * as the reply to the completion, and the agent carries on with its context intact; after
 * {@link #maxSendBacks()} such returns the run ends anyway and the open findings travel on the result
 * ({@link AgentResult#completionVerdict()}) for the host to act on.
 *
 * <p>Fails open: a check that cannot run (no evidence, a model error, an unreadable answer) accepts
 * the completion and says so on the verdict. It is a second pair of eyes, not a gate that can wedge a
 * run.
 */
public final class CompletionCheck {

    public static final int DEFAULT_SEND_BACKS = 2;

    private final String requirements;
    private final Supplier<String> evidence;
    private final int maxSendBacks;

    public CompletionCheck(String requirements, Supplier<String> evidence, int maxSendBacks) {
        this.requirements = requirements;
        this.evidence = evidence;
        this.maxSendBacks = Math.max(0, maxSendBacks);
    }

    public static CompletionCheck of(String requirements, Supplier<String> evidence) {
        return new CompletionCheck(requirements, evidence, DEFAULT_SEND_BACKS);
    }

    /** What the work must satisfy: the task as it was written. */
    public String requirements() { return requirements; }

    /** What was done, read from the work at the moment of completion. Null or blank: nothing to check. */
    public Supplier<String> evidence() { return evidence; }

    /** How many times a finding may send the run back before it ends with the finding open. */
    public int maxSendBacks() { return maxSendBacks; }

    /** Whether there is anything to check against. */
    boolean applies() {
        return requirements != null && !requirements.isBlank() && evidence != null;
    }
}
