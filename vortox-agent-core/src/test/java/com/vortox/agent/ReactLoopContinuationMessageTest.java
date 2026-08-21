package com.vortox.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a resumed conversation is handed the "carry on from here" instruction.
 *
 * <p>A snapshot taken when the loop ran out of iterations ends with the {@code user} message carrying
 * that round's {@code tool_result} blocks — the loop appends the results and then re-checks the
 * budget. Appending a fresh {@code user} message after it would put two user turns in a row, and
 * whether the Messages API merges those or rejects them with {@code roles must alternate} is not
 * worth leaving to chance in a path that only runs after a run has already gone wrong.
 *
 * <p>Only the approval gate had used {@code resume} before, and it appends an {@code assistant} turn
 * first, which is why this never surfaced.
 */
class ReactLoopContinuationMessageTest {

    private static final String INSTRUCTION = "Continue from where you left off.";

    /** The max-iterations shape: the snapshot's last turn is user, carrying tool_result blocks. */
    @Test
    void foldsTheInstructionIntoATrailingToolResultTurn() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                Map.of("role", "user", "content", "do the thing"),
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "tool_use", "id", "t1", "name", "read_file"))),
                Map.of("role", "user", "content", new ArrayList<>(List.of(
                        Map.of("type", "tool_result", "tool_use_id", "t1", "content", "file body"))))));

        ReactLoop.appendContinuationInstruction(messages, INSTRUCTION);

        assertEquals(3, messages.size(), "no extra turn — the instruction joins the last one");
        assertNoConsecutiveSameRole(messages);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(2).get("content");
        assertEquals(2, blocks.size());
        assertEquals("tool_result", blocks.get(0).get("type"), "the tool result must survive intact");
        assertEquals("text", blocks.get(1).get("type"));
        assertEquals(INSTRUCTION, blocks.get(1).get("text"));
    }

    /** A snapshot whose last user turn is plain text: append to the text rather than to a block list. */
    @Test
    void appendsToATrailingPlainTextUserTurn() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                Map.of("role", "assistant", "content", "working on it"),
                Map.of("role", "user", "content", "here is more detail")));

        ReactLoop.appendContinuationInstruction(messages, INSTRUCTION);

        assertEquals(2, messages.size());
        assertEquals("here is more detail\n\n" + INSTRUCTION, messages.get(1).get("content"));
        assertNoConsecutiveSameRole(messages);
    }

    /** The approval shape: last turn is assistant, so a new user turn is exactly right. */
    @Test
    void addsANewTurnWhenTheSnapshotEndsWithTheAssistant() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                Map.of("role", "user", "content", "do the thing"),
                Map.of("role", "assistant", "content", "done part one")));

        ReactLoop.appendContinuationInstruction(messages, INSTRUCTION);

        assertEquals(3, messages.size());
        assertEquals("user", messages.get(2).get("role"));
        assertEquals(INSTRUCTION, messages.get(2).get("content"));
        assertNoConsecutiveSameRole(messages);
    }

    @Test
    void addsTheOnlyTurnWhenThereIsNoHistory() {
        List<Map<String, Object>> messages = new ArrayList<>();

        ReactLoop.appendContinuationInstruction(messages, INSTRUCTION);

        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals(INSTRUCTION, messages.get(0).get("content"));
    }

    /** The snapshot may be immutable (List.of / Map.of) — folding must not try to mutate it in place. */
    @Test
    void doesNotMutateAnImmutableSnapshotEntry() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "tool_result", "tool_use_id", "t1", "content", "body")))));

        ReactLoop.appendContinuationInstruction(messages, INSTRUCTION);   // must not throw

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(0).get("content");
        assertEquals(2, blocks.size());
    }

    private static void assertNoConsecutiveSameRole(List<Map<String, Object>> messages) {
        for (int i = 1; i < messages.size(); i++) {
            assertTrue(!messages.get(i).get("role").equals(messages.get(i - 1).get("role")),
                    "consecutive " + messages.get(i).get("role") + " turns at index " + i);
        }
    }
}
