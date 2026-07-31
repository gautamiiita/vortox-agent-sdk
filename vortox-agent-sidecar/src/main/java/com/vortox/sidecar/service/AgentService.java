package com.vortox.sidecar.service;

import com.vortox.agent.AgentConfig;
import com.vortox.agent.AgentResult;
import com.vortox.agent.LlmClient;
import com.vortox.agent.OpenAiCompatibleLlmClient;
import com.vortox.agent.ReactLoop;
import com.vortox.agent.gateway.GatewayMemoryStore;
import com.vortox.agent.gateway.GatewayToolExecutor;
import com.vortox.agent.gateway.VortoxGateway;
import com.vortox.agent.spi.ActivityListener;
import com.vortox.agent.spi.ToolExecutor;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import com.vortox.sidecar.skill.VortoxSkillSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Refuse to inline anything bigger than this into a JSON response. */
    private static final long MAX_ARTIFACT_BYTES = 8L * 1024 * 1024;

    @Value("${sidecar.default-model:claude-sonnet-4-6}")
    private String defaultModel;

    @Value("${ANTHROPIC_API_KEY:}")
    private String envApiKey;

    @Value("${sidecar.max-tokens:4096}")
    private int defaultMaxTokens;

    /**
     * ReactLoop's built-in {@code execute_command} runs an arbitrary shell command in this
     * container, bypassing {@link ScriptToolExecutor} and therefore its per-run workspace
     * confinement — a single {@code cat /proc/1/environ} would hand the model this container's
     * API keys. {@link AgentConfig.Builder} defaults it on for embedded SDK users who own the
     * process they're running in; the sidecar executes model-chosen commands on a host with
     * bind-mounted skills and workspaces, so it defaults off here and skills are the whole tool
     * surface. Set {@code sidecar.enable-execute-command=true} to opt back in.
     */
    @Value("${sidecar.enable-execute-command:false}")
    private boolean enableExecuteCommand;

    /**
     * Refuse any run that arrives without a customer code.
     *
     * <p>Off by default because a deployment whose host application does not yet send one would stop
     * working entirely — the run executes fine without a code, it just cannot be attributed. Turn it
     * on once the host application reliably sends the code, and unattributed usage becomes
     * impossible rather than merely invisible.
     */
    @Value("${sidecar.require-tenant-code:false}")
    private boolean requireTenantCode;

    private final SkillRegistry skillRegistry;
    private final ScriptToolExecutor scriptToolExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Optional: present only when vortox.backend.url is configured. */
    @Nullable
    @Autowired(required = false)
    private GatewayMemoryStore gatewayMemoryStore;

    /**
     * Optional: present only when vortox.backend.url is configured. Used to pull a tenant's private
     * skills and secrets the first time that tenant is seen.
     */
    @Nullable
    @Autowired(required = false)
    private VortoxSkillSyncService skillSyncService;

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
        return run(request, UUID.randomUUID().toString(), ActivityListener.NOOP);
    }

    /**
     * Runs the agent under a caller-supplied runId with an {@link ActivityListener} wired in —
     * used by {@link ChatRunService} so its runId (already returned to the widget/poller) is the
     * exact same id {@link com.vortox.agent.ReactLoop} fires progress events under, letting a
     * stream registry keyed by that runId receive them.
     */
    public AgentRunResponse run(AgentRunRequest request, String runId, ActivityListener listener) {
        // Agent config from Vortox provides defaults, resolved for THIS run's tenant. A pooled
        // sidecar serves several institutions, and model, system prompt, availableSkills and the
        // agent's own API key all differ per tenant — taking another's would run the wrong agent
        // silently rather than failing.
        java.util.Map<String, Object> agentConfig = anthropicKeyRefreshService != null
                ? anthropicKeyRefreshService.getAgentConfig(emptyToNull(request.tenantCode())) : null;

        String llmProvider = firstNonBlank(request.llmProvider(),
                agentConfig != null ? (String) agentConfig.get("provider") : null);
        String llmBaseUrl = firstNonBlank(request.llmBaseUrl(),
                agentConfig != null ? (String) agentConfig.get("llmBaseUrl") : null);
        String model = firstNonBlank(request.model(),
                agentConfig != null ? (String) agentConfig.get("model") : null,
                defaultModel);
        String systemPrompt = firstNonBlank(request.systemPrompt(),
                agentConfig != null ? (String) agentConfig.get("systemPrompt") : null,
                "You are an autonomous AI agent. Use the available skills to complete the task.");

        // Explicit request value wins; otherwise defer to the linked agent's own cap (set from
        // Vortox, so it's controllable centrally per agent); otherwise a generous sidecar default.
        Integer maxIterations = request.maxIterations();
        if (maxIterations == null && agentConfig != null && agentConfig.get("maxIterations") instanceof Number n) {
            maxIterations = n.intValue();
        }
        if (maxIterations == null) maxIterations = 75;

        boolean isLocalLlm = "local".equalsIgnoreCase(llmProvider);
        String apiKey;
        if (isLocalLlm) {
            apiKey = firstNonBlank(request.apiKey(),
                    agentConfig != null ? (String) agentConfig.get("apiKey") : null);
        } else {
            apiKey = firstNonBlank(request.apiKey(),
                    agentConfig != null ? (String) agentConfig.get("apiKey") : null,
                    resolveApiKey(null));
        }

        if (apiKey == null || apiKey.isBlank()) {
            String hint = isLocalLlm
                    ? "No API key provided for local LLM. Set it on the linked agent in Vortox or pass apiKey in the request."
                    : "No API key provided. Set ANTHROPIC_API_KEY, configure the platform key in Vortox, or link an agent to this SDK app.";
            return errorResponse(runId, hint);
        }

        // Skills: prefer explicit request list; fall back to agent's availableSkills from vortox.
        List<String> skillNames = request.skills();
        if ((skillNames == null || skillNames.isEmpty()) && agentConfig != null) {
            @SuppressWarnings("unchecked")
            List<String> agentSkills = (List<String>) agentConfig.get("availableSkills");
            if (agentSkills != null && !agentSkills.isEmpty()) skillNames = agentSkills;
        }

        // A tenant's private skills and its secrets are fetched on first sight of that tenant, so the
        // sidecar learns its tenants from traffic instead of having to enumerate them.
        String tenantCode = request.tenantCode();
        if (emptyToNull(tenantCode) == null && requireTenantCode) {
            // Attribution is not optional here: a run with no customer code is recorded against
            // nobody, so it silently disappears from usage, quotas and the per-customer switch.
            // Refusing is louder than a number that quietly under-reports.
            log.warn("Run {} refused: no customer code, and sidecar.require-tenant-code is on", runId);
            return errorResponse(runId, "No customer code was supplied. This assistant is configured "
                    + "to require one so every run can be attributed to a customer.");
        }
        if (emptyToNull(tenantCode) == null && vortoxGateway != null) {
            // With a pooled SDK key Vortox refuses every untenanted call, so the run will execute but
            // nothing about it is recorded. Worth saying once per run rather than leaving an empty
            // tracking screen as the only clue that the host application isn't sending its tenant.
            log.warn("Run {} has no tenant code — if this SDK key is pooled, skills, secrets and "
                    + "usage tracking will all be refused. The host application must send tenantCode.",
                    runId);
        }
        if (tenantCode != null && !tenantCode.isBlank() && skillSyncService != null) {
            skillSyncService.ensureTenantSynced(tenantCode);
        }

        List<SkillDefinition> activeSkills = skillRegistry.subsetFor(tenantCode, skillNames);
        List<java.util.Map<String, Object>> toolDefs = activeSkills.stream()
                .map(SkillDefinition::toToolDefinition)
                .toList();

        // The executor is bound to this run's tenant, so nothing downstream — including ReactLoop —
        // can name a different one: ToolExecutor.execute has no tenant parameter. The gateway
        // executor wraps it, routing propose_new_skill / send_notification to the Vortox control
        // plane and delegating skill tools to the tenant-bound executor locally.
        ToolExecutor tenantBound = scriptToolExecutor.forTenant(emptyToNull(tenantCode));
        ToolExecutor activeExecutor = vortoxGateway != null
                ? new GatewayToolExecutor(vortoxGateway, tenantBound)
                : tenantBound;

        log.info("Agent run {} — tenant={} task='{}' model={} provider={} skills={} gateway={}", runId,
                tenantCode, truncate(request.task(), 80), model, llmProvider,
                activeSkills.stream().map(SkillDefinition::name).toList(),
                vortoxGateway != null ? "enabled" : "disabled");

        if (vortoxGateway != null) {
            // Report the linked agent rather than the literal "sidecar-agent" this always sent, so
            // runs in Vortox attribute to the agent that actually handled them — per tenant, since a
            // pooled deployment resolves a different agent for each.
            String reportedAgentId = agentConfig != null && agentConfig.get("agentId") instanceof String a
                    ? a : "sidecar-agent";
            vortoxGateway.createRun(runId, reportedAgentId, truncate(request.task(), 500), model,
                    emptyToNull(tenantCode));
        }

        AgentConfig.Builder configBuilder = AgentConfig.builder()
                .apiKey(apiKey)
                .model(model)
                .maxIterations(maxIterations)
                .systemPrompt(systemPrompt)
                .tools(toolDefs)
                .toolExecutor(activeExecutor)
                .enableExecuteCommand(enableExecuteCommand)
                .activityListener(listener);

        LlmClient llmClient = resolveLlmClient(llmProvider, llmBaseUrl);
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
                    result.inputTokens(), result.outputTokens(), emptyToNull(tenantCode));
        }

        List<AgentRunResponse.ToolCallDto> calls = result.toolCalls().stream()
                .map(tc -> new AgentRunResponse.ToolCallDto(tc.toolName(), tc.success(), tc.durationMs()))
                .toList();

        List<AgentRunResponse.ArtifactDto> artifacts =
                extractArtifacts(emptyToNull(tenantCode), result.toolCalls(), runId);

        return new AgentRunResponse(
                runId,
                result.status().name(),
                result.response() != null ? result.response() : result.error(),
                calls,
                result.inputTokens(),
                result.outputTokens(),
                artifacts
        );
    }

    /**
     * Reads back the bytes of any file written by a skill that declares {@code produces_artifact}
     * in its SKILL.md, using the path it reports (either in its input or its output, per that
     * declaration) — no extra skill/LLM round trip needed, and no sidecar code change needed to
     * support a new file-producing skill. The sidecar's local disk is ephemeral and not reachable
     * from outside this process, so the bytes are inlined here and the file is deleted immediately
     * after being read.
     * <p>
     * Keeps only the <em>last</em> successful call per skill. Some artifact-producing skills
     * (e.g. a SQL-to-CSV export) write a file as a side effect of every successful call, not just
     * a final "save my result" action — if the agent calls the same skill several times while
     * exploring or retrying (wrong column names, refining a query), only the last, presumably
     * correct result should be surfaced as a download; earlier attempts' files are deleted here
     * rather than left to accumulate or all shown to the user as if each were a separate result.
     */
    /* package-private for testability */
    List<AgentRunResponse.ArtifactDto> extractArtifacts(List<AgentResult.ToolCall> toolCalls, String runId) {
        return extractArtifacts(null, toolCalls, runId);
    }

    /**
     * As above, resolving each tool's {@code produces_artifact} declaration as the run's tenant sees
     * it — a tenant overriding a shared skill may declare a different artifact field, and the
     * declaration that governs is the body that actually ran.
     */
    /* package-private for testability */
    List<AgentRunResponse.ArtifactDto> extractArtifacts(String tenantCode,
                                                        List<AgentResult.ToolCall> toolCalls, String runId) {
        java.util.Map<String, String> latestPathByTool = new java.util.LinkedHashMap<>();
        for (AgentResult.ToolCall tc : toolCalls) {
            if (!tc.success()) continue;
            SkillDefinition.ProducesArtifact declaration = skillRegistry.findFor(tenantCode, tc.toolName())
                    .map(SkillDefinition::producesArtifact)
                    .orElse(null);
            if (declaration == null) continue;

            String pathStr = declaration.isInputSourced()
                    ? asString(tc.input() != null ? tc.input().get(declaration.pathField()) : null)
                    : jsonStringField(tc.output(), declaration.pathField());
            if (pathStr == null || pathStr.isBlank()) continue;

            String supersededPath = latestPathByTool.put(tc.toolName(), pathStr);
            if (supersededPath != null && !supersededPath.equals(pathStr)) {
                try {
                    Files.deleteIfExists(Path.of(supersededPath));
                } catch (Exception e) {
                    log.warn("Run {}: failed to clean up superseded artifact '{}': {}",
                            runId, supersededPath, e.getMessage());
                }
            }
        }

        List<AgentRunResponse.ArtifactDto> artifacts = new java.util.ArrayList<>();
        for (String pathStr : latestPathByTool.values()) {
            try {
                Path path = Path.of(pathStr);
                if (!Files.isRegularFile(path)) continue;

                long size = Files.size(path);
                if (size > MAX_ARTIFACT_BYTES) {
                    log.warn("Run {}: skipping artifact '{}' — {} bytes exceeds cap of {}",
                            runId, path, size, MAX_ARTIFACT_BYTES);
                    continue;
                }

                byte[] bytes = Files.readAllBytes(path);
                String mimeType = firstNonBlank(Files.probeContentType(path), "application/octet-stream");
                String filename = path.getFileName().toString();

                artifacts.add(new AgentRunResponse.ArtifactDto(
                        filename, mimeType, bytes.length, Base64.getEncoder().encodeToString(bytes)));

                Files.deleteIfExists(path);
            } catch (Exception e) {
                log.warn("Run {}: failed to read artifact at '{}': {}", runId, pathStr, e.getMessage());
            }
        }
        return artifacts;
    }

    private static String asString(Object v) {
        return v instanceof String s ? s : null;
    }

    /** Parses {@code json} as an object and returns the string value at {@code key}, or null on any failure. */
    private String jsonStringField(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            java.util.Map<?, ?> parsed = objectMapper.readValue(json, java.util.Map.class);
            return asString(parsed.get(key));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveApiKey(String requestKey) {
        if (requestKey != null && !requestKey.isBlank()) return requestKey;
        if (anthropicKeyRefreshService != null) {
            String vortoxKey = anthropicKeyRefreshService.getKey();
            if (vortoxKey != null && !vortoxKey.isBlank()) return vortoxKey;
        }
        return envApiKey;
    }

    /** Normalises a blank tenant code to null, so "no tenant" has exactly one representation. */
    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Returns the first non-null, non-blank string from the candidates, or null if none. */
    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) return s;
        }
        return null;
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
        return new AgentRunResponse(runId, "FAILED", message, List.of(), 0, 0, List.of());
    }
}
