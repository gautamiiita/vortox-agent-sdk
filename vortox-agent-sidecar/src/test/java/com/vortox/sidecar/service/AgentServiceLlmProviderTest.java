package com.vortox.sidecar.service;

import com.vortox.agent.AnthropicClient;
import com.vortox.agent.LlmClient;
import com.vortox.agent.OpenAiCompatibleLlmClient;
import com.vortox.agent.spi.ActivityListener;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The model backend is configuration, not code: an OpenAI-compatible server at any path, a local
 * one needing no key, or an Anthropic gateway. Before, the path was fixed to the ELCA gateway's,
 * a key was always demanded, and a "local" run with no URL quietly went to Anthropic.
 */
class AgentServiceLlmProviderTest {

    private final List<String> keysSeen = new ArrayList<>();
    private final List<String> providersResolved = new ArrayList<>();
    private AgentService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry();
        ReflectionTestUtils.setField(registry, "skillsPath", tmp.resolve("skills").toString());
        ScriptToolExecutor scripts = mock(ScriptToolExecutor.class);
        when(scripts.forTenant(any())).thenReturn((name, params, runId) -> "{}");

        LlmClient llm = (system, messages, tools, apiKey, model) -> {
            keysSeen.add(apiKey);
            AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
            r.setContent(List.of(AnthropicClient.ContentBlock.text("done")));
            r.setStopReason("end_turn");
            return r;
        };
        service = new AgentService(registry, scripts) {
            @Override
            LlmClient resolveLlmClient(String provider, String baseUrl) {
                providersResolved.add(provider + "@" + baseUrl);
                return llm;
            }
        };
        ReflectionTestUtils.setField(service, "defaultModel", "m");
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 1000);
    }

    private AgentRunResponse run(String apiKey, String provider, String baseUrl) {
        return service.run(new AgentRunRequest("hi", null, null, null, 3, apiKey, provider, baseUrl),
                "run-1", ActivityListener.NOOP);
    }

    // ── Endpoint resolution ───────────────────────────────────────────────────

    @Test
    @DisplayName("a base URL already ending in /chat/completions is used as it is")
    void fullUrlKept() {
        assertThat(AgentService.completionsUrl("local", "http://ollama:11434/v1/chat/completions/", null))
                .isEqualTo("http://ollama:11434/v1/chat/completions");
        assertThat(AgentService.completionsUrl("local", "https://ai.svc.elca.ch/api/chat/completions", "/x"))
                .isEqualTo("https://ai.svc.elca.ch/api/chat/completions");
    }

    @Test
    @DisplayName("a bare base URL gets the configured path, else the provider's default")
    void pathAppended() {
        assertThat(AgentService.completionsUrl("local", "https://ai.svc.elca.ch/", null))
                .isEqualTo("https://ai.svc.elca.ch/api/chat/completions");
        assertThat(AgentService.completionsUrl("openai", "https://api.openai.com", null))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(AgentService.completionsUrl("local", "http://vllm:8000", "v1/chat/completions"))
                .isEqualTo("http://vllm:8000/v1/chat/completions");
    }

    @Test
    @DisplayName("no base URL: OpenAI's own endpoint for openai, nothing for local")
    void noBaseUrl() {
        assertThat(AgentService.completionsUrl("openai", null, null))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(AgentService.completionsUrl("local", " ", null)).isNull();
    }

    @Test
    @DisplayName("local, openai and openai-compatible are OpenAI-compatible; claude and blank are not")
    void providerNames() {
        assertThat(AgentService.isOpenAiCompatible("local")).isTrue();
        assertThat(AgentService.isOpenAiCompatible(" OpenAI ")).isTrue();
        assertThat(AgentService.isOpenAiCompatible("openai-compatible")).isTrue();
        assertThat(AgentService.isOpenAiCompatible("claude")).isFalse();
        assertThat(AgentService.isOpenAiCompatible(null)).isFalse();
    }

    // ── Client selection (real resolveLlmClient) ──────────────────────────────

    @Test
    @DisplayName("the real resolver builds an OpenAI client for the resolved URL, honouring the configured path")
    void resolverBuildsOpenAiClient() {
        AgentService real = new AgentService(new SkillRegistry(), mock(ScriptToolExecutor.class));
        ReflectionTestUtils.setField(real, "llmCompletionsPath", "/v1/chat/completions");

        LlmClient client = real.resolveLlmClient("local", "http://ollama:11434");

        assertThat(client).isInstanceOf(OpenAiCompatibleLlmClient.class);
        assertThat(((OpenAiCompatibleLlmClient) client).completionsUrl())
                .isEqualTo("http://ollama:11434/v1/chat/completions");
    }

    @Test
    @DisplayName("Anthropic goes to the stock client unless a gateway base URL is configured")
    void resolverAnthropicGateway() {
        AgentService real = new AgentService(new SkillRegistry(), mock(ScriptToolExecutor.class));
        ReflectionTestUtils.setField(real, "defaultModel", "m");
        assertThat(real.resolveLlmClient("claude", null)).isNull();

        ReflectionTestUtils.setField(real, "anthropicBaseUrl", "https://gw.example.com/");
        LlmClient client = real.resolveLlmClient("claude", "https://ignored");

        assertThat(client).isInstanceOf(AnthropicClient.class);
        assertThat(((AnthropicClient) client).apiUrl()).isEqualTo("https://gw.example.com/v1/messages");
    }

    // ── Runs ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a local server needing no key runs without one")
    void localWithoutKey() {
        AgentRunResponse r = run(null, "local", "http://ollama:11434/v1/chat/completions");

        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(keysSeen).hasSize(1);
        assertThat(keysSeen.get(0)).isNull();
    }

    @Test
    @DisplayName("local with no URL anywhere is refused, instead of falling through to Anthropic")
    void localWithoutUrlRefused() {
        AgentRunResponse r = run("k", "local", null);

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.result()).contains("No endpoint configured");
        assertThat(keysSeen).isEmpty();
    }

    @Test
    @DisplayName("sidecar.llm.* defaults apply when the request names no provider")
    void sidecarDefaults() {
        ReflectionTestUtils.setField(service, "defaultLlmProvider", "openai");
        ReflectionTestUtils.setField(service, "defaultLlmApiKey", "sk-llm");
        ReflectionTestUtils.setField(service, "envApiKey", "sk-ant-never-sent");

        AgentRunResponse r = run(null, null, null);

        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(providersResolved).containsExactly("openai@null");
        assertThat(keysSeen).containsExactly("sk-llm");
    }

    @Test
    @DisplayName("Anthropic still demands a key")
    void anthropicNeedsKey() {
        AgentRunResponse r = run(null, null, null);

        assertThat(r.status()).isEqualTo("FAILED");
        assertThat(r.result()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    @DisplayName("an Anthropic agent switched to another provider does not lend it its key")
    void agentKeyNotLentAcrossProviders() {
        AnthropicKeyRefreshService keys = mock(AnthropicKeyRefreshService.class);
        when(keys.getAgentConfig(any())).thenReturn(Map.of(
                "agentId", "a1", "provider", "claude", "apiKey", "sk-ant-agent"));
        ReflectionTestUtils.setField(service, "anthropicKeyRefreshService", keys);

        AgentRunResponse r = run(null, "local", "http://elsewhere/v1/chat/completions");

        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(keysSeen).hasSize(1);
        assertThat(keysSeen.get(0)).isNull();
    }
}
