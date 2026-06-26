package com.vortox.agent.starter;

/**
 * Thrown by {@link AgentClient} when the sidecar is unreachable, returns a non-200 status,
 * or the agent run completes with a non-SUCCESS result.
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
