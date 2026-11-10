package com.vortox.agent;

/**
 * A spending cap for one run, checked by {@link ReactLoop} after every model reply.
 *
 * <p>The loop counts tokens; only the host knows what they cost (its prices, the model each side call
 * used, what its own tools spent on sub-runs). So the host prices the spend and says whether the run
 * is over. A run that is over ends as PARTIAL with its conversation, so it can be resumed or looked at,
 * the way a run at its iteration limit does. Like Claude Code's {@code max_budget_usd}, the check runs
 * after a reply, so a run can go over by the reply that crossed the line.
 */
@FunctionalInterface
public interface BudgetGuard {

    /**
     * What the run has spent so far, in tokens.
     *
     * @param input        input tokens, side calls included
     * @param output       output tokens, side calls included
     * @param cacheWrite   cache-write tokens, side calls included
     * @param cacheWrite1h the part of {@code cacheWrite} written to one-hour entries (main loop)
     * @param cacheRead    cache-read tokens, side calls included
     * @param check        the completion checks' share {in, out, cacheWrite, cacheRead}, made on their
     *                     own model and already counted in the totals above
     */
    record Spend(long input, long output, long cacheWrite, long cacheWrite1h, long cacheRead, long[] check) {}

    /** Why the run must stop — e.g. "spent $5.12 of its $5.00 cap" — or null to carry on. */
    String overBudget(Spend spend);

    /** No cap. */
    BudgetGuard NONE = spend -> null;
}
