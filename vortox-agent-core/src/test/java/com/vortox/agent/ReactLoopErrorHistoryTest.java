package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model call that cannot be completed ends the run with the conversation it had built up, so the
 * platform can resume it. It used to return none, and a dropped connection forty iterations in sent
 * the retry back to iteration 1 with nothing it had read or done.
 */
class ReactLoopErrorHistoryTest {

    /** One tool round, then the connection fails. */
    static final class FailingClient implements LlmClient {
        final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();

        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            return turns.isEmpty()
                    ? AnthropicClient.ClaudeResponse.error("Exception: ConnectException (caused by UnresolvedAddressException)")
                    : turns.poll();
        }
    }

    private static AnthropicClient.ClaudeResponse toolTurn() {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text("Looking first."),
                AnthropicClient.ContentBlock.toolUse("t1", "no_such_tool", Map.of("q", "x"))));
        r.setStopReason("tool_use");
        r.setInputTokens(100);
        r.setOutputTokens(20);
        r.setCacheReadInputTokens(5000);
        return r;
    }

    @Test
    @DisplayName("an LLM failure after a tool round returns the conversation, ending on the tool results")
    void failureCarriesHistory() {
        FailingClient client = new FailingClient();
        client.turns.add(toolTurn());
        AgentConfig config = AgentConfig.builder().agentId("dev-agent-002").apiKey("k").maxIterations(10).build();

        AgentResult r = new ReactLoop(config, client, Runnable::run).run("Fix the bug.", null, "run-1", null, null, "t", null);

        assertTrue(r.isError(), String.valueOf(r.status()));
        assertTrue(r.error().contains("UnresolvedAddressException"), r.error());
        List<Map<String, Object>> history = r.conversationHistory();
        assertEquals(3, history.size(), String.valueOf(history));
        assertEquals("user", history.get(0).get("role"));
        assertEquals("assistant", history.get(1).get("role"));
        assertEquals("user", history.get(2).get("role"));
        assertTrue(String.valueOf(history.get(2).get("content")).contains("t1"), "the tool round is answered");
        assertEquals(5000, r.cacheReadTokens());
        assertEquals(1, r.toolCalls().size());
    }

    @Test
    @DisplayName("a failure on the very first call returns the task message alone")
    void firstCallFailure() {
        AgentConfig config = AgentConfig.builder().agentId("dev-agent-002").apiKey("k").maxIterations(10).build();

        AgentResult r = new ReactLoop(config, new FailingClient(), Runnable::run).run("Fix the bug.", null, "run-1", null, null, "t", null);

        assertTrue(r.isError());
        assertEquals(1, r.conversationHistory().size());
        assertEquals("user", r.conversationHistory().get(0).get("role"));
    }

    @Test
    @DisplayName("a trailing assistant turn is cut, so the resumed request is valid")
    void trailingAssistantIsCut() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "task"),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use", "id", "t1"))));

        assertEquals(List.of(messages.get(0)), ReactLoop.resumableHistory(messages));
        assertEquals(List.of(), ReactLoop.resumableHistory(null));
    }
}
