package com.vortox.sidecar.service;

import com.vortox.agent.AgentConfig;
import com.vortox.agent.AgentResult;
import com.vortox.agent.ReactLoop;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Value("${sidecar.default-model:claude-sonnet-4-6}")
    private String defaultModel;

    @Value("${ANTHROPIC_API_KEY:}")
    private String envApiKey;

    private final SkillRegistry skillRegistry;
    private final ScriptToolExecutor toolExecutor;

    public AgentService(SkillRegistry skillRegistry, ScriptToolExecutor toolExecutor) {
        this.skillRegistry = skillRegistry;
        this.toolExecutor  = toolExecutor;
    }

    public AgentRunResponse run(AgentRunRequest request) {
        String runId = UUID.randomUUID().toString();

        String apiKey = resolveApiKey(request.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            return errorResponse(runId, "No API key provided. Set ANTHROPIC_API_KEY or pass apiKey in the request.");
        }

        List<SkillDefinition> activeSkills = skillRegistry.subset(request.skills());
        List<java.util.Map<String, Object>> toolDefs = activeSkills.stream()
                .map(SkillDefinition::toToolDefinition)
                .toList();

        log.info("Agent run {} — task='{}' skills={}", runId,
                truncate(request.task(), 80), activeSkills.stream().map(SkillDefinition::name).toList());

        AgentConfig config = AgentConfig.builder()
                .apiKey(apiKey)
                .model(request.model() != null ? request.model() : defaultModel)
                .maxIterations(request.maxIterations() != null ? request.maxIterations() : 100)
                .systemPrompt(request.systemPrompt() != null ? request.systemPrompt()
                        : "You are an autonomous AI agent. Use the available skills to complete the task.")
                .tools(toolDefs)
                .toolExecutor(toolExecutor)
                .build();

        AgentResult result = new ReactLoop(config).run(request.task(), runId);

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
        return envApiKey;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static AgentRunResponse errorResponse(String runId, String message) {
        return new AgentRunResponse(runId, "FAILED", message, List.of(), 0, 0);
    }
}
