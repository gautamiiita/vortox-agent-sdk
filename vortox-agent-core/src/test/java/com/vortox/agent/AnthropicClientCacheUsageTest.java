package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One-hour cache writes are priced at 2x input, five-minute ones at 1.25x (2026-10-18): the response
 * says how many tokens went to each, and the run's cost is only right if that split is kept.
 */
class AnthropicClientCacheUsageTest {

    private static AnthropicClient client() {
        return new AnthropicClient(new ObjectMapper(), "claude-opus-5", 1024, "http://unused", 1000);
    }

    @Test
    @DisplayName("the one-hour part of a cache write is read from usage.cache_creation")
    void oneHourSplit() {
        AnthropicClient.ClaudeResponse r = client().parseResponse("""
                {"id":"msg_1","model":"claude-opus-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":3,"output_tokens":5,"cache_read_input_tokens":900,
                          "cache_creation_input_tokens":700,
                          "cache_creation":{"ephemeral_5m_input_tokens":200,"ephemeral_1h_input_tokens":500}}}""");
        assertEquals(700, r.getCacheCreationInputTokens());
        assertEquals(500, r.getCacheCreation1hInputTokens());
    }

    @Test
    @DisplayName("an endpoint that gives no split reports no one-hour writes")
    void noSplit() {
        AnthropicClient.ClaudeResponse r = client().parseResponse("""
                {"id":"msg_1","model":"claude-opus-5","stop_reason":"end_turn",
                 "content":[{"type":"text","text":"ok"}],
                 "usage":{"input_tokens":3,"output_tokens":5,"cache_creation_input_tokens":700}}""");
        assertEquals(700, r.getCacheCreationInputTokens());
        assertEquals(0, r.getCacheCreation1hInputTokens());
    }
}
