package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The loop sends a turn's thinking back with that turn, signed and unchanged, so the model continues
 * its reasoning instead of starting it again. Thinking without a signature cannot be sent back and
 * is left out.
 */
class ReactLoopThinkingTest {

    @Test
    @DisplayName("the next request carries the previous turn's signed thinking, before its tool call")
    void thinkingTravelsWithItsTurn() {
        List<List<Map<String, Object>>> sent = new ArrayList<>();
        LlmClient client = (sys, messages, tools, key, model) -> {
            sent.add(new ArrayList<>(messages));
            AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
            if (sent.size() == 1) {
                r.setContent(List.of(
                        AnthropicClient.ContentBlock.thinking("", "sig-1"),
                        AnthropicClient.ContentBlock.thinking("unsigned", ""),
                        AnthropicClient.ContentBlock.toolUse("t1", "lookup", Map.of("q", "x"))));
                r.setStopReason("tool_use");
            } else {
                r.setContent(List.of(AnthropicClient.ContentBlock.text("Done.")));
                r.setStopReason("end_turn");
            }
            return r;
        };
        AgentConfig config = AgentConfig.builder().apiKey("k").maxIterations(5)
                .toolExecutor((name, params, runId) -> "found")
                .enableMemoryTools(false).build();

        AgentResult result = new ReactLoop(config, client, Runnable::run).run("Find x.", "run-1");

        assertTrue(result.isSuccess(), String.valueOf(result.error()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assistant = (List<Map<String, Object>>) sent.get(1).get(1).get("content");
        assertEquals(2, assistant.size(), String.valueOf(assistant));
        assertEquals("thinking", assistant.get(0).get("type"));
        assertEquals("sig-1", assistant.get(0).get("signature"));
        assertEquals("", assistant.get(0).get("thinking"));
        assertEquals("tool_use", assistant.get(1).get("type"));
    }
}
