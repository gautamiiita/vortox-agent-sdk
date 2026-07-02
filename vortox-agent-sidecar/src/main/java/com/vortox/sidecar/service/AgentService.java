package com.vortox.sidecar.service;

import com.vortox.agent.AgentConfig;
import com.vortox.agent.AgentResult;
import com.vortox.agent.LlmClient;
import com.vortox.agent.OpenAiCompatibleLlmClient;
import com.vortox.agent.ReactLoop;
import com.vortox.agent.gateway.GatewayMemoryStore;
import com.vortox.agent.gateway.GatewayToolExecutor;
import com.vortox.agent.gateway.VortoxGateway;
import com.vortox.agent.spi.ToolExecutor;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Value("${sidecar.default-model:claude-sonnet-4-6}")
    private String defaultModel;

    @Value("${ANTHROPIC_API_KEY:}")
    private String envApiKey;

    @Value("${sidecar.max-tokens:4096}")
    private int defaultMaxTokens;

    private final SkillRegistry skillRegistry;
    private final ScriptToolExecutor scriptToolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Optional: present only when vortox.backend.url is configured. */
    @Nullable
    @Autowired(required = false)
    private GatewayToolExecutor gatewayToolExecutor;

    /** Optional: present only when vortox.backend.url is configured. */
    @Nullable
    @Autowired(required = false)
    private GatewayMemoryStore gatewayMemoryStore;

    /** Optional: present only when vortox.backend.url is configured. */
    @Nullable
    @Autowired(required = false)
    private VortoxGateway vortoxGateway;

    /** Optional: present only when vortox.backend.url is configured. */
    @Nullable
    @Autowired(required = false)
    private AnthropicKeyRefreshService anthropicKeyRefreshService;

    public AgentService(SkillRegistry skillRegistry, ScriptToolExecutor scriptToolExecutor) {
        this.skillRegistry      = skillRegistry;
        this.scriptToolExecutor = scriptToolExecutor;
    }

    public AgentRunResponse run(AgentRunRequest request) {
        String runId = UUID.randomUUID().toString();

        boolean isLocalLlm = "local".equalsIgnoreCase(request.llmProvider());
        String apiKey = isLocalLlm ? request.apiKey() : resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            String hint = isLocalLlm
                    ? "No API key provided for local LLM. Pass apiKey (Bearer token) in the request."
                    : "No API key provided. Set ANTHROPIC_API_KEY or pass apiKey in the request.";
            return errorResponse(runId, hint);
        }

        List<SkillDefinition> activeSkills = skillRegistry.subset(request.skills());
        List<java.util.Map<String, Object>> toolDefs = activeSkills.stream()
                .map(SkillDefinition::toToolDefinition)
                .toList();

        // Gateway executor wraps ScriptToolExecutor: routes propose_new_skill / send_notification
        // to Vortox control plane and delegates all skill tools to ScriptToolExecutor locally.
        ToolExecutor activeExecutor = gatewayToolExecutor != null ? gatewayToolExecutor : scriptToolExecutor;

        String model = request.model() != null ? request.model() : defaultModel;

        log.info("Agent run {} — task='{}' model={} skills={} gateway={}", runId,
                truncate(request.task(), 80), model,
                activeSkills.stream().map(SkillDefinition::name).toList(),
                gatewayToolExecutor != null ? "enabled" : "disabled");

        if (vortoxGateway != null) {
            vortoxGateway.createRun(runId, "sidecar-agent", truncate(request.task(), 500), model);
        }

        AgentConfig.Builder configBuilder = AgentConfig.builder()
                .apiKey(apiKey)
                .model(model)
                .maxIterations(request.maxIterations() != null ? request.maxIterations() : 100)
                .systemPrompt(request.systemPrompt() != null ? request.systemPrompt()
                        : "You are an autonomous AI agent. Use the available skills to complete the task.")
                .tools(toolDefs)
                .toolExecutor(activeExecutor);

        LlmClient llmClient = resolveLlmClient(request.llmProvider(), request.llmBaseUrl());
        if (llmClient != null) {
            configBuilder.llmClient(llmClient);
        }

        if (gatewayMemoryStore != null) {
            configBuilder.memoryStore(gatewayMemoryStore);
        }

        AgentResult result = new ReactLoop(configBuilder.build()).run(request.task(), runId);

        if (vortoxGateway != null) {
            vortoxGateway.updateRun(runId, result.status().name(),
                    result.response() != null ? result.response() : result.error(),
                    result.inputTokens(), result.outputTokens());
        }

        List<AgentRunResponse.ToolCallDto> calls = result.toolCalls().stream()
                .map(tc -> new AgentRunResponse.ToolCallDto(tc.toolName(), tc.success(), tc.durationMs()))
                .toList();

        return new AgentRunResponse(
                runId,
                result.status().name(),
                result.response() != null ? result.response() : result.error(),
                calls,
                result.inputTokens(),
                result.outputTokens()
        );
    }

    private String resolveApiKey(String requestKey) {
        if (requestKey != null && !requestKey.isBlank()) return requestKey;
        if (anthropicKeyRefreshService != null) {
            String vortoxKey = anthropicKeyRefreshService.getKey();
            if (vortoxKey != null && !vortoxKey.isBlank()) return vortoxKey;
        }
        return envApiKey;
    }

    /** Returns an LlmClient when provider is "local", null otherwise (ReactLoop uses AnthropicClient). */
    private LlmClient resolveLlmClient(String llmProvider, String llmBaseUrl) {
        if ("local".equalsIgnoreCase(llmProvider) && llmBaseUrl != null && !llmBaseUrl.isBlank()) {
            return new OpenAiCompatibleLlmClient(objectMapper, defaultMaxTokens, llmBaseUrl);
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static AgentRunResponse errorResponse(String runId, String message) {
        return new AgentRunResponse(runId, "FAILED", message, List.of(), 0, 0);
    }
}
