package com.vortox.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AnthropicClient#withCacheBreakpoint} — the fix that lets a growing ReAct-loop
 * conversation get an incremental prompt-cache hit instead of paying fresh-input price for the
 * whole history on every iteration. Only the last message's last content block should ever be
 * marked, and earlier messages/blocks must be left completely untouched.
 */
class AnthropicClientCacheBreakpointTest {

    @Test
    void returnsEmptyOrNullUnchanged() {
        assertNull(AnthropicClient.withCacheBreakpoint(null));
        assertTrue(AnthropicClient.withCacheBreakpoint(List.of()).isEmpty());
    }

    @Test
    void marksLastBlockOfStringContentMessage() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "hello"));

        List<Map<String, Object>> result = AnthropicClient.withCacheBreakpoint(messages);

        assertEquals(1, result.size());
        List<?> blocks = (List<?>) result.get(0).get("content");
        Map<?, ?> block = (Map<?, ?>) blocks.get(0);
        assertEquals("hello", block.get("text"));
        assertEquals(Map.of("type", "ephemeral"), block.get("cache_control"));
    }

    @Test
    void marksOnlyLastBlockOfListContentMessage() {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "tool_result", "tool_use_id", "t1", "content", "first"));
        content.add(Map.of("type", "tool_result", "tool_use_id", "t2", "content", "second"));
        messages.add(Map.of("role", "user", "content", content));

        List<Map<String, Object>> result = AnthropicClient.withCacheBreakpoint(messages);

        List<?> blocks = (List<?>) result.get(0).get("content");
        Map<?, ?> first = (Map<?, ?>) blocks.get(0);
        Map<?, ?> last = (Map<?, ?>) blocks.get(1);
        assertFalse(first.containsKey("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), last.get("cache_control"));
    }

    @Test
    void onlyLastMessageIsMarkedNotEarlierOnes() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "first turn"));
        messages.add(Map.of("role", "assistant", "content", "second turn"));
        messages.add(Map.of("role", "user", "content", "third turn"));

        List<Map<String, Object>> result = AnthropicClient.withCacheBreakpoint(messages);

        assertInstanceOf(String.class, result.get(0).get("content"));
        assertInstanceOf(String.class, result.get(1).get("content"));
        List<?> lastBlocks = (List<?>) result.get(2).get("content");
        Map<?, ?> lastBlock = (Map<?, ?>) lastBlocks.get(0);
        assertEquals(Map.of("type", "ephemeral"), lastBlock.get("cache_control"));
    }

    @Test
    void doesNotMutateTheOriginalMessagesList() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", "hello"));

        AnthropicClient.withCacheBreakpoint(messages);

        assertEquals("hello", messages.get(0).get("content"));
    }
}
