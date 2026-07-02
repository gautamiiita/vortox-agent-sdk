package com.vortox.agent;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over LLM backends.
 *
 * <p>Implementations translate the Anthropic-format message/tool representation that
 * {@link ReactLoop} works with into whatever wire format the backing service expects,
 * then translate the response back.  The default implementation is
 * {@link AnthropicClient}; {@link OpenAiCompatibleLlmClient} covers any OpenAI-compatible
 * endpoint (local models, ELCA AI gateway, etc.).</p>
 */
public interface LlmClient {

    /**
     * Send a multi-turn conversation.
     *
     * @param systemPrompt the system prompt (may be null)
     * @param messages     alternating user/assistant messages in Anthropic wire format
     * @param tools        Anthropic-format tool definitions (may be null/empty)
     * @param apiKey       API key or Bearer token for the backing service
     * @param model        model ID to use
     * @return parsed response (never null; errors are wrapped in {@link AnthropicClient.ClaudeResponse#error})
     */
    AnthropicClient.ClaudeResponse send(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String apiKey,
            String model);
}
