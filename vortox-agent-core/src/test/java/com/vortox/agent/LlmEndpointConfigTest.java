package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Endpoints and keys are configuration: any URL for either client, no key for a keyless server. */
class LlmEndpointConfigTest {

    @Test
    void messagesUrlFollowsTheBaseUrlConvention() {
        assertEquals("https://gw.example.com/v1/messages", AnthropicClient.messagesUrl("https://gw.example.com"));
        assertEquals("https://gw.example.com/v1/messages", AnthropicClient.messagesUrl(" https://gw.example.com// "));
        assertEquals("https://gw.example.com/v1/messages", AnthropicClient.messagesUrl("https://gw.example.com/v1/messages"));
    }

    @Test
    void anthropicClientPostsWhereItIsTold() {
        AnthropicClient c = new AnthropicClient(new ObjectMapper(), "m", 100, "https://gw.example.com/v1/messages");
        assertEquals("https://gw.example.com/v1/messages", c.apiUrl());
    }

    @Test
    void openAiClientUrls() {
        assertEquals("https://ai.svc.elca.ch/api/chat/completions",
                new OpenAiCompatibleLlmClient(new ObjectMapper(), 100, "https://ai.svc.elca.ch/").completionsUrl());
        assertEquals("http://ollama:11434/v1/chat/completions",
                OpenAiCompatibleLlmClient.forEndpoint(new ObjectMapper(), 100,
                        "http://ollama:11434/v1/chat/completions").completionsUrl());
    }

    @Test
    void aSuppliedClientNeedsNoKeyTheDefaultOneDoes() {
        LlmClient keyless = OpenAiCompatibleLlmClient.forEndpoint(new ObjectMapper(), 100, "http://x/v1/chat/completions");
        AgentConfig config = AgentConfig.builder().llmClient(keyless).build();
        assertNull(config.getApiKey());

        assertThrows(IllegalStateException.class, () -> AgentConfig.builder().build());
    }
}
