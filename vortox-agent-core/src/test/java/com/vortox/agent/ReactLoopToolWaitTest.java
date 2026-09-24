package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long the loop waits for a tool call. The failure this guards against: a command allowed 300 s
 * was abandoned by the loop at 120 s, reported to the model as timed out while it kept running, and
 * the model's retry stacked a second copy on top of it.
 */
class ReactLoopToolWaitTest {

    @Test
    @DisplayName("a tool that names no timeout gets the default")
    void defaultWait() {
        assertEquals(120, ReactLoop.toolWaitSeconds(Map.of("command", "ls")));
        assertEquals(120, ReactLoop.toolWaitSeconds(null));
    }

    @Test
    @DisplayName("a long command is waited for longer than its own timeout, so that timeout fires first")
    void outlastsTheToolsOwnTimeout() {
        int wait = ReactLoop.toolWaitSeconds(Map.of("timeout_seconds", 300));
        assertTrue(wait > 300, "the loop gave up before the command's own 300 s limit");
    }

    @Test
    @DisplayName("the wait is capped where the tool's timeout is capped")
    void capped() {
        assertEquals(630, ReactLoop.toolWaitSeconds(Map.of("timeout_seconds", 5_000)));
    }

    @Test
    @DisplayName("a short or malformed timeout never shortens the default")
    void neverBelowDefault() {
        assertEquals(120, ReactLoop.toolWaitSeconds(Map.of("timeout_seconds", 10)));
        assertEquals(120, ReactLoop.toolWaitSeconds(Map.of("timeout_seconds", "soon")));
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("timeout_seconds", null);
        assertEquals(120, ReactLoop.toolWaitSeconds(nullValue));
        assertEquals(330, ReactLoop.toolWaitSeconds(Map.of("timeout_seconds", "300")));
    }
}
