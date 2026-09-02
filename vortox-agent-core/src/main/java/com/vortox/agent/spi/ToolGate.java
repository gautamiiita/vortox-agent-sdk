package com.vortox.agent.spi;

import java.util.Map;

/**
 * Decides, before a tool runs, whether the host wants a human to approve this particular call.
 *
 * <p><strong>Why this is not a {@link ToolExecutor} concern.</strong> {@code request_approval} lets
 * the <em>agent</em> ask permission, which means the gate only closes when the model chooses to
 * knock. A host that wants to impose a policy — this run may read but not write, this run needs a
 * person to see every shell command — has to be able to stop a call the model did not volunteer.
 * And it has to stop it <em>before</em> dispatch: {@link com.vortox.agent.ReactLoop} persists the
 * assistant turn and then executes every tool in that turn in parallel, so an exception thrown from
 * inside the executor arrives after the side effect it was supposed to prevent.
 *
 * <p>A gated call pauses the whole run and surfaces as
 * {@link com.vortox.agent.AgentResult.Status#APPROVAL_NEEDED}, indistinguishable to the host from an
 * agent-initiated approval: same snapshot fields, same
 * {@link com.vortox.agent.ReactLoop#resumeAfterApproval} to continue. The decision text becomes the
 * gated call's tool result, so approving it tells the model to go ahead and rejecting it tells the
 * model why not.
 *
 * <p>The gate is consulted for every tool the host supplied and for the built-ins the loop handles
 * itself, so an implementation must recognise names it does not care about and allow them. Returning
 * a request for a control tool such as {@code task_complete} would deadlock the run — there would be
 * no way to finish it.
 */
@FunctionalInterface
public interface ToolGate {

    /** Allows every call. The default when a host configures no gate. */
    ToolGate OPEN = (toolName, params) -> null;

    /**
     * @param toolName the exact tool name from the LLM's {@code tool_use} block
     * @param params   the {@code input} map from the same block
     * @return null to let the call proceed, or the text of the approval request to put in front of a
     *     human. The text should name the action concretely — a reviewer approving a shell command
     *     needs to see the command.
     */
    String approvalRequestFor(String toolName, Map<String, Object> params);
}
