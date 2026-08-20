package com.vortox.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a run identifies itself in the log.
 *
 * <p>Every line a run emits — the iteration, the LLM turn, the error — was tagged with the run id
 * alone. With several agents working in parallel on one backend, that opaque UUID told a reader
 * which run a line belonged to but not which agent was talking to the LLM, nor what about; finding
 * out meant carrying the id to another screen. The tag now names all three.
 */
class ReactLoopRunTagTest {

    private static ReactLoop loopFor(String agentId) {
        return new ReactLoop(AgentConfig.builder()
                .agentId(agentId)
                .apiKey("test-key")
                .build());
    }

    @Test
    void namesTheAgentTheTaskIdAndTheTask() {
        String tag = loopFor("dev-agent-002").runTag("0235d836", "Complete the simulation engine");

        assertEquals("dev-agent-002 · 0235d836 · 'Complete the simulation engine'", tag);
    }

    /**
     * The SDK is used outside Vortox, where there is no agent registry and no task title. The id
     * must still be there on its own — a tag is never allowed to become the reason a line cannot
     * be traced back to its run.
     */
    @Test
    void fallsBackToTheRunIdAloneWhenNothingElseIsKnown() {
        assertEquals("run-42", loopFor(null).runTag("run-42", null));
        assertEquals("run-42", loopFor("  ").runTag("run-42", "  "));
    }

    /** A task title is free text: one long one must not own the whole log line. */
    @Test
    void truncatesALongTitle() {
        String title = "Complete queuelab/engine.py simulation engine (resume — focus on correctness "
                + "first, then performance)";

        String tag = loopFor("dev-agent-002").runTag("0235d836", title);

        assertTrue(tag.endsWith("…'"), tag);
        assertTrue(tag.length() < "dev-agent-002 · 0235d836 · ".length() + title.length(), tag);
    }

    /** A title spanning lines would break the one-line-per-event shape the log relies on. */
    @Test
    void keepsTheTagOnOneLine() {
        String tag = loopFor("ba-agent-001").runTag("t-1", "Write the spec\nthen review it");

        assertEquals("ba-agent-001 · t-1 · 'Write the spec then review it'", tag);
    }
}
