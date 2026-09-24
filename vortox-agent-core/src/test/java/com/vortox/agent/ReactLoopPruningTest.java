package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conversation pruning — what a long run still remembers, and what it costs to remember it.
 *
 * <p>The property that matters most is the one that has nothing to do with memory: below the high
 * water mark this must not touch the message list at all. The cache breakpoint sits on the last
 * message, so the cached prefix is the whole history, and rewriting any earlier message invalidates
 * it from that point on. The rule this replaced stubbed one more round on every iteration past the
 * fifth — changing the prefix on every single call, missing the cache for everything after the
 * change, and paying the cache-write surcharge on content the next round would invalidate again.
 *
 * <p>The second property is that keeping is budgeted in characters, not rounds. A single tool
 * result may be 40,000 characters, so "keep the last N rounds" is a promise to keep up to N ×
 * 40,000 — safe for small results and ruinous for large ones at the same N.
 */
class ReactLoopPruningTest {

    private static void prune(List<Map<String, Object>> messages) {
        ReactLoop.pruneConversationHistory(messages);
    }

    /** One assistant turn calling a tool, plus the user turn carrying its result. */
    private static void addRound(List<Map<String, Object>> messages, String reasoning, String result) {
        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "t" + messages.size());
        toolUse.put("name", "read_file");
        toolUse.put("input", Map.of("path", "x.java"));

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", reasoning);

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", new ArrayList<>(List.of(text, toolUse)));
        messages.add(assistant);

        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "t" + (messages.size() - 1));
        toolResult.put("content", result);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", new ArrayList<>(List.of(toolResult)));
        messages.add(user);
    }

    private static String filler(int chars) {
        return "x".repeat(chars);
    }

    private static List<Map<String, Object>> conversation(int rounds, int resultChars) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("role", "user");
        first.put("content", "Do the thing.");
        messages.add(first);
        for (int i = 0; i < rounds; i++) {
            addRound(messages, "Round " + i + " reasoning. " + filler(800), filler(resultChars));
        }
        return messages;
    }

    /** A deep copy, so "was anything mutated" is answerable. */
    private static String snapshot(List<Map<String, Object>> messages) {
        return messages.toString();
    }

    @Nested
    @DisplayName("leaving the prompt cache alone")
    class CacheSafety {

        @Test
        @DisplayName("a light conversation is not touched at all, however many rounds it has")
        void doesNotMutateBelowTheHighWaterMark() {
            // Forty rounds — far past the five the old rule kept — but small ones.
            List<Map<String, Object>> messages = conversation(40, 500);
            String before = snapshot(messages);

            prune(messages);

            // Byte-identical: the cached prefix survives, which is the whole point.
            assertEquals(before, snapshot(messages));
        }

        @Test
        @DisplayName("pruning once leaves the next several rounds untouched")
        void hasHysteresisRatherThanPruningEveryRound() {
            List<Map<String, Object>> messages = conversation(30, 20_000);

            prune(messages);
            String afterFirstPrune = snapshot(messages);

            // A couple more rounds arrive and the loop prunes again, as it does every round.
            addRound(messages, "next", filler(2_000));
            prune(messages);
            addRound(messages, "next", filler(2_000));
            prune(messages);

            // The older part of the conversation is unchanged, so the prefix still matches and
            // those two calls were cache hits rather than two fresh copies of the history.
            String olderPartBefore = afterFirstPrune.substring(0, 2_000);
            assertTrue(snapshot(messages).startsWith(olderPartBefore),
                    "the older part of the history was rewritten, so the cached prefix was lost");
        }
    }

    @Nested
    @DisplayName("budgeting in characters, not rounds")
    class Budget {

        @Test
        @DisplayName("a heavy conversation is cut back")
        void prunesWhenItGetsHeavy() {
            List<Map<String, Object>> messages = conversation(40, 20_000);
            long before = ReactLoop.charsOf(messages, 0, messages.size());
            assertTrue(before > 320_000, "fixture should exceed the high water mark");

            prune(messages);

            assertTrue(ReactLoop.charsOf(messages, 0, messages.size()) < before,
                    "a conversation over the high water mark should have been cut back");
        }

        @Test
        @DisplayName("few enormous rounds are kept where many small ones would be")
        void keepsFewerRoundsWhenTheyAreLarger() {
            List<Map<String, Object>> big = conversation(40, 40_000);
            List<Map<String, Object>> small = conversation(40, 4_000);

            prune(big);
            prune(small);

            // The point of budgeting in characters: the same rule keeps far more of the small
            // conversation, where counting rounds would have kept exactly five of each.
            assertTrue(unprunedRounds(small) > unprunedRounds(big),
                    "budgeting in characters should keep more small rounds than large ones");
        }

        @Test
        @DisplayName("the most recent rounds survive however large they are")
        void alwaysKeepsTheNewestRounds() {
            List<Map<String, Object>> messages = conversation(10, 60_000);

            prune(messages);

            // An agent that cannot see what it just did is worse than one carrying some weight.
            assertTrue(snapshot(messages).contains(filler(40_000)),
                    "the newest rounds must survive however large they are");
        }
    }

    @Nested
    @DisplayName("long runs whose tool inputs are heavy")
    class HeavyInputs {

        /** A round that writes a file: the weight is in the tool_use input, not the result. */
        private void addWriteRound(List<Map<String, Object>> messages, int contentChars) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("path", "src/File" + messages.size() + ".java");
            input.put("content", filler(contentChars));

            Map<String, Object> toolUse = new LinkedHashMap<>();
            toolUse.put("type", "tool_use");
            toolUse.put("id", "w" + messages.size());
            toolUse.put("name", "write_file");
            toolUse.put("input", input);
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", new ArrayList<>(List.of(toolUse)));
            messages.add(assistant);

            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("type", "tool_result");
            toolResult.put("tool_use_id", "w" + (messages.size() - 1));
            toolResult.put("content", "{\"success\":true}");
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", new ArrayList<>(List.of(toolResult)));
            messages.add(user);
        }

        @Test
        @DisplayName("after one prune, the next rounds leave the history alone")
        void keepsHysteresisWhenInputsAreHeavy() {
            // 150 rounds × 4,000 chars of write_file content: the stubbed residue alone used to stay
            // over HIGH, so every subsequent round re-pruned and rewrote the prefix.
            List<Map<String, Object>> messages = conversation(0, 0);
            int prunes = 0;
            for (int i = 0; i < 400 && prunes < 2; i++) {
                addWriteRound(messages, 4_000);
                long before = ReactLoop.charsOf(messages, 0, messages.size());
                prune(messages);
                if (ReactLoop.charsOf(messages, 0, messages.size()) < before) prunes++;
            }
            assertEquals(2, prunes, "fixture should have pruned twice");
            String afterPrune = snapshot(messages);

            for (int i = 0; i < 10; i++) {
                addWriteRound(messages, 4_000);
                prune(messages);
            }

            assertTrue(snapshot(messages).startsWith(afterPrune.substring(0, afterPrune.length() - 1)),
                    "ten more rounds rewrote earlier history, so every one of them missed the cache");
        }

        @Test
        @DisplayName("dropping rounds keeps the task, role alternation and tool_use/tool_result pairs")
        void droppingRoundsKeepsTheConversationValid() {
            List<Map<String, Object>> messages = conversation(0, 0);
            for (int i = 0; i < 300; i++) addWriteRound(messages, 4_000);

            prune(messages);

            assertTrue(String.valueOf(messages.get(0).get("content")).startsWith("Do the thing."));
            assertTrue(String.valueOf(messages.get(0).get("content")).contains(ReactLoop.DROPPED_ROUNDS_NOTE));
            for (int i = 1; i < messages.size(); i++) {
                String expected = (i % 2 == 1) ? "assistant" : "user";
                assertEquals(expected, messages.get(i).get("role"), "roles must alternate at " + i);
            }
            for (int i = 1; i < messages.size(); i += 2) {
                Map<?, ?> use = (Map<?, ?>) ((List<?>) messages.get(i).get("content")).get(0);
                Map<?, ?> result = (Map<?, ?>) ((List<?>) messages.get(i + 1).get("content")).get(0);
                assertEquals(use.get("id"), result.get("tool_use_id"), "tool_use split from its result");
            }
            assertTrue(ReactLoop.charsOf(messages, 0, messages.size()) < 240_000);

            // The file path survives stubbing even where the content does not.
            Map<?, ?> oldestUse = (Map<?, ?>) ((List<?>) messages.get(1).get("content")).get(0);
            assertTrue(((Map<?, ?>) oldestUse.get("input")).get("path").toString().startsWith("src/File"));
        }
    }

    private static long unprunedRounds(List<Map<String, Object>> messages) {
        return messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .filter(m -> m.get("content") instanceof List)
                .flatMap(m -> ((List<?>) m.get("content")).stream())
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(b -> "tool_result".equals(b.get("type")))
                .filter(b -> !String.valueOf(b.get("content")).endsWith("[pruned]"))
                .count();
    }
}
