package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An empty final turn is not an answer. Seen on the Data Studio assistant: a run that had called
 * several tools ended with an empty end_turn, and the widget showed "I couldn't generate a
 * response. Please try again." over work that had been done.
 */
class ReactLoopEmptyReplyTest {

    /** Plays scripted turns and keeps a copy of every request's messages. */
    static final class Scripted implements LlmClient {
        final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();
        final List<List<Map<String, Object>>> requests = new ArrayList<>();

        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            requests.add(List.copyOf(messages));
            return turns.poll();
        }
    }

    private static AnthropicClient.ClaudeResponse turn(String stop, AnthropicClient.ContentBlock... blocks) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(blocks));
        r.setStopReason(stop);
        return r;
    }

    private static AnthropicClient.ClaudeResponse empty() { return turn("end_turn"); }

    private static AnthropicClient.ClaudeResponse toolTurn(String text) {
        return turn("tool_use", AnthropicClient.ContentBlock.text(text),
                AnthropicClient.ContentBlock.toolUse("t1", "no_such_tool", Map.of("q", "x")));
    }

    private static AgentResult run(Scripted client) {
        AgentConfig config = AgentConfig.builder().agentId("a").apiKey("k").maxIterations(10).build();
        return new ReactLoop(config, client, Runnable::run).run("Find the vouchers.", null, "run-1", null, null, "t", null);
    }

    @Test
    @DisplayName("an empty end_turn is asked once for the answer, and that answer is the reply")
    void emptyTurnIsAskedForTheAnswer() {
        Scripted client = new Scripted();
        client.turns.add(toolTurn("Looking at the tables."));
        client.turns.add(empty());
        client.turns.add(turn("end_turn", AnthropicClient.ContentBlock.text("Found 4 voucher tables.")));

        AgentResult r = run(client);

        assertTrue(r.isSuccess(), String.valueOf(r.status()));
        assertEquals("Found 4 voucher tables.", r.response());
        List<Map<String, Object>> third = client.requests.get(2);
        Map<String, Object> last = third.get(third.size() - 1);
        assertEquals("user", last.get("role"));
        String content = String.valueOf(last.get("content"));
        assertTrue(content.contains("tool_result") && content.contains(ReactLoop.EMPTY_TURN_TEXT),
                "the nudge rides on the tool results, after them: " + content);
        assertEquals(third.size(), client.requests.get(1).size(), "the empty assistant turn is not kept");
    }

    @Test
    @DisplayName("empty twice: the last prose of the run is the reply")
    void emptyTwiceFallsBackToEarlierProse() {
        Scripted client = new Scripted();
        client.turns.add(toolTurn("Here is what I found: 4 voucher tables."));
        client.turns.add(empty());
        client.turns.add(empty());

        AgentResult r = run(client);

        assertTrue(r.isSuccess());
        assertEquals("Here is what I found: 4 voucher tables.", r.response());
    }

    @Test
    @DisplayName("empty twice with no prose anywhere is an error that says so, not a blank success")
    void emptyWithNothingIsAnError() {
        Scripted client = new Scripted();
        client.turns.add(empty());
        client.turns.add(empty());

        AgentResult r = run(client);

        assertTrue(r.isError(), String.valueOf(r.status()));
        assertTrue(r.error().contains("without a reply"), r.error());
    }

    @Test
    @DisplayName("the nudge is added as a user message when the conversation does not end on one")
    void nudgeWithoutTrailingUser() {
        List<Map<String, Object>> messages = new ArrayList<>(List.of(
                Map.of("role", "user", "content", "task"),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "text", "text", "hi")))));
        ReactLoop.withEmptyTurnNudge(messages);
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(2).get("role"));

        List<Map<String, Object>> plain = new ArrayList<>(List.of(Map.of("role", "user", "content", "task")));
        ReactLoop.withEmptyTurnNudge(plain);
        assertEquals(1, plain.size());
        String content = String.valueOf(plain.get(0).get("content"));
        assertTrue(content.contains("task") && content.contains(ReactLoop.EMPTY_TURN_TEXT), content);
    }
}
