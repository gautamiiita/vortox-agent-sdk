package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reply that ran out of output tokens is answered, not executed.
 *
 * <p>Seen in production: an agent writing a whole test file in one {@code file_append} was cut off
 * after {@code path} and before {@code content}. The call ran, failed validation with
 * "$.content is missing", and the model — told nothing about why — sent the identical oversized
 * call 18 more times before happening on a smaller one.
 */
class ReactLoopTruncatedTurnTest {

    private static Map<String, Object> toolUse(String id, String name) {
        return Map.of("type", "tool_use", "id", id, "name", name,
                "input", Map.of("path", "src/routes/ingredients.test.ts"));
    }

    private static Map<String, Object> text(String body) {
        return Map.of("type", "text", "text", body);
    }

    @Test
    @DisplayName("a cut-off tool call is answered with an error that says to split the work")
    void cutOffCallIsExplained() {
        List<Map<String, Object>> reply = ReactLoop.truncatedTurnReply(
                List.of(text("Writing the tests now."), toolUse("t1", "file_append")));

        assertEquals(1, reply.size());
        Map<String, Object> result = reply.get(0);
        assertEquals("tool_result", result.get("type"));
        assertEquals("t1", result.get("tool_use_id"));
        assertEquals(true, result.get("is_error"));
        String content = String.valueOf(result.get("content"));
        assertTrue(content.contains("output token limit"), content);
        assertTrue(content.contains("file_append"), "should say how to recover: " + content);
    }

    @Test
    @DisplayName("every tool_use in the turn is answered, as the Messages API requires")
    void everyCallAnswered() {
        List<Map<String, Object>> reply = ReactLoop.truncatedTurnReply(
                List.of(toolUse("t1", "file_read"), toolUse("t2", "file_write")));

        assertEquals(List.of("t1", "t2"), reply.stream().map(r -> r.get("tool_use_id")).toList());
    }

    @Test
    @DisplayName("a cut-off text reply is told to continue rather than accepted as the answer")
    void cutOffTextContinues() {
        List<Map<String, Object>> reply = ReactLoop.truncatedTurnReply(
                List.of(text("Here is the full report: | col | col |")));

        assertEquals(1, reply.size());
        assertEquals("text", reply.get(0).get("type"));
        assertEquals(ReactLoop.TRUNCATED_TEXT, reply.get(0).get("text"));
    }

    @Test
    @DisplayName("a null or id-less turn still yields a valid user message")
    void degradedInput() {
        assertEquals("text", ReactLoop.truncatedTurnReply(null).get(0).get("type"));
        assertEquals("text", ReactLoop.truncatedTurnReply(
                List.of(Map.of("type", "tool_use", "name", "file_write"))).get(0).get("type"));
    }
}
