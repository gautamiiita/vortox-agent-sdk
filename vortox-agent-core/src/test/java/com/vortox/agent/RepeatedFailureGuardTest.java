package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same failing call, sent again and again, is called out and then stopped — the shape that cost
 * a Pantry run ~70 of its 142 iterations ("content is required", with an empty call each time).
 */
class RepeatedFailureGuardTest {

    private static final Map<String, Object> EMPTY = Map.of();
    private static final String ERR = "write_file failed: content is required";

    @Test
    @DisplayName("an identical failure is annotated from the third time, and the run stops at the eighth")
    void warnsThenStops() {
        RepeatedFailureGuard g = new RepeatedFailureGuard();
        for (int i = 1; i < RepeatedFailureGuard.STOP_AT; i++) {
            int n = g.record("write_file", EMPTY, false, ERR);
            assertEquals(i, n);
            String seen = RepeatedFailureGuard.annotate(ERR, n);
            assertEquals(i >= RepeatedFailureGuard.WARN_AT, seen.contains("[Loop guard]"), "at " + i);
            assertNull(g.stopReason(), "not yet at " + i);
        }
        g.record("write_file", EMPTY, false, ERR);
        assertNotNull(g.stopReason());
        assertTrue(g.stopReason().contains("write_file"), g.stopReason());
        assertTrue(g.stopReason().contains("content is required"), g.stopReason());
    }

    @Test
    @DisplayName("different arguments or a different error are a different attempt, and successes count as nothing")
    void onlyIdenticalFailuresCount() {
        RepeatedFailureGuard g = new RepeatedFailureGuard();
        for (int i = 0; i < 20; i++) {
            assertEquals(1, g.record("edit_file", Map.of("path", "f" + i + ".ts"), false, "old_string not found"));
            assertEquals(0, g.record("read_file", Map.of("path", "a.ts"), true, "ok"));
        }
        assertEquals(1, g.record("edit_file", Map.of("path", "f0.ts"), false, "a different error"));
        assertNull(g.stopReason());
    }

    @Test
    @DisplayName("a success in between does not reset the count of an identical failure")
    void probesDoNotReset() {
        RepeatedFailureGuard g = new RepeatedFailureGuard();
        g.record("execute_command", EMPTY, false, "command parameter is required");
        g.record("execute_command", Map.of("command", "pwd"), true, "/app");
        assertEquals(2, g.record("execute_command", EMPTY, false, "command parameter is required"));
    }

    @Test
    @DisplayName("below the threshold the result is passed through untouched")
    void passThrough() {
        assertEquals(ERR, RepeatedFailureGuard.annotate(ERR, 0));
        assertEquals(ERR, RepeatedFailureGuard.annotate(ERR, 2));
    }

    @Test
    @DisplayName("a memory call with entries yields each entry; without, the call is the one entry")
    void memoryEntries() {
        List<Map<String, Object>> many = ReactLoop.memoryEntries(Map.of("entries", List.of(
                Map.of("key", "build_result", "content", "BUILD OK"),
                "not an object",
                Map.of("key", "test_result", "content", "88 passed"))));
        assertEquals(List.of("build_result", "test_result"), many.stream().map(e -> e.get("key")).toList());

        Map<String, Object> single = Map.of("key", "files_changed", "content", "a.ts");
        assertEquals(List.of(single), ReactLoop.memoryEntries(single));
        assertEquals(List.of(), ReactLoop.memoryEntries(null));
    }
}
