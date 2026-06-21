package com.vortox.agent;

import com.vortox.agent.spi.ActivityListener;
import com.vortox.agent.spi.MemoryStore;
import com.vortox.agent.spi.InMemoryStore;
import com.vortox.agent.spi.TaskSpawner;
import com.vortox.agent.spi.ToolExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a {@link ReactLoop} run.
 *
 * Build instances with {@link #builder()}:
 * <pre>{@code
 * AgentConfig config = AgentConfig.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .systemPrompt("You are a helpful assistant.")
 *     .tool("search_web", schema, (name, params, runId) -> callSearchApi(params))
 *     .maxIterations(50)
 *     .build();
 *
 * AgentResult result = new ReactLoop(config).run("What is the weather in Paris?");
 * }</pre>
 */
public final class AgentConfig {

    // ── LLM ──────────────────────────────────────────────────────────────────
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final int maxIterations;
    private final String systemPrompt;

    // ── Tools ─────────────────────────────────────────────────────────────────
    /** Caller-supplied tool definitions in Claude {@code tool_use} format. */
    private final List<Map<String, Object>> tools;
    /** Executes any tool not handled as a built-in. */
    private final ToolExecutor toolExecutor;

    // ── Built-in feature flags ────────────────────────────────────────────────
    private final boolean enableTaskComplete;
    private final boolean enableClarification;
    private final boolean enableApproval;
    private final boolean enableHandoff;
    private final boolean enableMemoryTools;
    private final boolean enableExecuteCommand;
    private final boolean enableSpawnTask;

    // ── SPI implementations ───────────────────────────────────────────────────
    private final MemoryStore memoryStore;
    private final ActivityListener activityListener;
    private final TaskSpawner taskSpawner;

    private AgentConfig(Builder b) {
        this.apiKey              = b.apiKey;
        this.model               = b.model;
        this.maxTokens           = b.maxTokens;
        this.maxIterations       = b.maxIterations;
        this.systemPrompt        = b.systemPrompt;
        this.tools               = Collections.unmodifiableList(new ArrayList<>(b.tools));
        this.toolExecutor        = b.toolExecutor;
        this.enableTaskComplete  = b.enableTaskComplete;
        this.enableClarification = b.enableClarification;
        this.enableApproval      = b.enableApproval;
        this.enableHandoff       = b.enableHandoff;
        this.enableMemoryTools   = b.enableMemoryTools;
        this.enableExecuteCommand = b.enableExecuteCommand;
        this.enableSpawnTask     = b.enableSpawnTask;
        this.memoryStore         = b.memoryStore;
        this.activityListener    = b.activityListener;
        this.taskSpawner         = b.taskSpawner;
    }

    public static Builder builder() { return new Builder(); }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getApiKey()              { return apiKey; }
    public String getModel()               { return model; }
    public int getMaxTokens()              { return maxTokens; }
    public int getMaxIterations()          { return maxIterations; }
    public String getSystemPrompt()        { return systemPrompt; }
    public List<Map<String, Object>> getTools() { return tools; }
    public ToolExecutor getToolExecutor()  { return toolExecutor; }
    public boolean isEnableTaskComplete()  { return enableTaskComplete; }
    public boolean isEnableClarification() { return enableClarification; }
    public boolean isEnableApproval()      { return enableApproval; }
    public boolean isEnableHandoff()       { return enableHandoff; }
    public boolean isEnableMemoryTools()   { return enableMemoryTools; }
    public boolean isEnableExecuteCommand(){ return enableExecuteCommand; }
    public boolean isEnableSpawnTask()     { return enableSpawnTask; }
    public MemoryStore getMemoryStore()    { return memoryStore; }
    public ActivityListener getActivityListener() { return activityListener; }
    public TaskSpawner getTaskSpawner()    { return taskSpawner; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private String apiKey;
        private String model            = "claude-sonnet-4-6";
        private int maxTokens           = 4096;
        private int maxIterations       = 100;
        private String systemPrompt     = "You are an autonomous AI agent that executes tasks using available tools.";
        private final List<Map<String, Object>> tools = new ArrayList<>();
        private ToolExecutor toolExecutor = (name, params, runId) -> {
            throw new ToolExecutor.ToolExecutionException("No ToolExecutor configured for tool: " + name);
        };
        private boolean enableTaskComplete   = true;
        private boolean enableClarification  = true;
        private boolean enableApproval       = true;
        private boolean enableHandoff        = true;
        private boolean enableMemoryTools    = true;
        private boolean enableExecuteCommand = true;
        private boolean enableSpawnTask      = false;
        private MemoryStore memoryStore      = new InMemoryStore();
        private ActivityListener activityListener = ActivityListener.NOOP;
        private TaskSpawner taskSpawner      = null;

        public Builder apiKey(String apiKey)                   { this.apiKey = apiKey; return this; }
        public Builder model(String model)                     { this.model = model; return this; }
        public Builder maxTokens(int maxTokens)                { this.maxTokens = maxTokens; return this; }
        public Builder maxIterations(int maxIterations)        { this.maxIterations = maxIterations; return this; }
        public Builder systemPrompt(String systemPrompt)       { this.systemPrompt = systemPrompt; return this; }
        public Builder toolExecutor(ToolExecutor executor)     { this.toolExecutor = executor; return this; }
        public Builder memoryStore(MemoryStore store)          { this.memoryStore = store; return this; }
        public Builder activityListener(ActivityListener l)    { this.activityListener = l; return this; }
        public Builder taskSpawner(TaskSpawner spawner)        { this.taskSpawner = spawner; this.enableSpawnTask = true; return this; }

        /** Register a single tool definition (Claude {@code tool_use} format). */
        public Builder tool(Map<String, Object> toolDefinition) {
            this.tools.add(toolDefinition);
            return this;
        }

        /** Register multiple tool definitions at once. */
        public Builder tools(List<Map<String, Object>> toolDefinitions) {
            this.tools.addAll(toolDefinitions);
            return this;
        }

        public Builder enableTaskComplete(boolean v)   { this.enableTaskComplete = v; return this; }
        public Builder enableClarification(boolean v)  { this.enableClarification = v; return this; }
        public Builder enableApproval(boolean v)       { this.enableApproval = v; return this; }
        public Builder enableHandoff(boolean v)        { this.enableHandoff = v; return this; }
        public Builder enableMemoryTools(boolean v)    { this.enableMemoryTools = v; return this; }
        public Builder enableExecuteCommand(boolean v) { this.enableExecuteCommand = v; return this; }

        public AgentConfig build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey is required");
            }
            return new AgentConfig(this);
        }
    }
}
