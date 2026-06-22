package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.spi.ActivityListener;
import com.vortox.agent.spi.MemoryStore;
import com.vortox.agent.spi.TaskSpawner;
import com.vortox.agent.spi.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.LinkedHashSet;

/**
 * Stateless ReAct (Reasoning + Acting) loop over the Anthropic Messages API.
 *
 * <p>Create one instance per agent configuration; call {@link #run} for each
 * individual task.  The instance is thread-safe — multiple tasks can run in
 * parallel through the same {@code ReactLoop}.</p>
 *
 * <pre>{@code
 * AgentConfig config = AgentConfig.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .systemPrompt("You are a helpful assistant.")
 *     .toolExecutor(myExecutor)
 *     .tools(myToolDefinitions)
 *     .build();
 *
 * ReactLoop loop = new ReactLoop(config);
 * AgentResult result = loop.run("Summarise the quarterly report.");
 * }</pre>
 */
public final class ReactLoop {

    private static final Logger log = LoggerFactory.getLogger(ReactLoop.class);

    // ── Built-in tool names ───────────────────────────────────────────────────
    static final String TASK_COMPLETE_TOOL      = "task_complete";
    static final String CLARIFICATION_TOOL      = "request_clarification";
    static final String APPROVAL_TOOL           = "request_approval";
    static final String HANDOFF_TOOL            = "request_handoff";
    static final String REMEMBER_TOOL           = "remember_memory";
    static final String RECALL_TOOL             = "recall_memory";
    static final String WRITE_TASK_MEMORY_TOOL  = "write_task_memory";
    static final String READ_TASK_MEMORY_TOOL   = "read_task_memory";
    static final String EXECUTE_COMMAND_TOOL    = "execute_command";
    static final String SPAWN_TASK_TOOL         = "spawn_task";

    // ── Conversation pruning constants ────────────────────────────────────────
    private static final int MAX_TOOL_RESULT_CHARS         = 1_500;
    private static final int MAX_REPORT_CHARS              = 12_000;
    private static final int PRUNE_KEEP_RECENT_ROUNDS      = 5;
    private static final int PRUNED_RESULT_MAX_CHARS       = 400;
    private static final int PRUNED_ASSISTANT_TEXT_MAX_CHARS = 200;

    private final AgentConfig config;
    private final AnthropicClient client;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ReactLoop(AgentConfig config) {
        this(config, new AnthropicClient(new ObjectMapper(), config.getModel(), config.getMaxTokens()),
                ForkJoinPool.commonPool());
    }

    public ReactLoop(AgentConfig config, AnthropicClient client, Executor executor) {
        this.config       = config;
        this.client       = client;
        this.objectMapper = new ObjectMapper();
        this.executor     = executor;
    }

    // ── Public run methods ────────────────────────────────────────────────────

    /** Run a fresh task from the beginning. */
    public AgentResult run(String userMessage) {
        return run(userMessage, UUID.randomUUID().toString(), null, null, null);
    }

    /** Run with a caller-supplied correlation ID. */
    public AgentResult run(String userMessage, String runId) {
        return run(userMessage, runId, null, null, null);
    }

    /** Run with a self-verification gate: the agent must confirm it met {@code expectedOutcome}
     *  before the first SUCCESS is accepted. */
    public AgentResult runWithExpectedOutcome(String userMessage, String runId, String expectedOutcome) {
        return run(userMessage, runId, expectedOutcome, null, null);
    }

    /** Resume from a prior partial conversation snapshot (e.g. after max-iterations or approval). */
    public AgentResult resume(String userMessage, String runId,
                               List<Map<String, Object>> priorMessages) {
        return run(userMessage, runId, null, priorMessages, null);
    }

    /** Run with expectedOutcome verification and optional prior conversation snapshot. */
    public AgentResult run(String userMessage, String runId,
                           String expectedOutcome, List<Map<String, Object>> priorMessages) {
        return run(userMessage, runId, expectedOutcome, priorMessages, null);
    }

    /**
     * Resume after a human approved (or rejected) a paused approval gate.
     *
     * @param conversationSnapshot the {@link AgentResult#conversationHistory()} from the paused run
     * @param assistantContent     the {@link AgentResult#approvalAssistantContent()} from the paused run
     * @param toolUseId            the {@link AgentResult#approvalToolUseId()} from the paused run
     * @param decisionMessage      human decision text fed back to the LLM as the tool result
     */
    public AgentResult resumeAfterApproval(String runId,
                                            List<Map<String, Object>> conversationSnapshot,
                                            List<Map<String, Object>> assistantContent,
                                            String toolUseId,
                                            String decisionMessage) {
        log.info("ReactLoop: resuming after approval for run {}", runId);
        List<Map<String, Object>> messages = new ArrayList<>(conversationSnapshot);
        messages.add(Map.of("role", "assistant", "content", assistantContent));

        Map<String, Object> toolResult = new HashMap<>();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", toolUseId);
        toolResult.put("content", decisionMessage);
        messages.add(Map.of("role", "user", "content", List.of(toolResult)));

        return run(null, runId, null, messages, null);
    }

    // ── Core loop ─────────────────────────────────────────────────────────────

    private AgentResult run(String userMessage,
                             String runId,
                             String expectedOutcome,
                             List<Map<String, Object>> priorMessages,
                             String agentId) {

        List<Map<String, Object>> tools    = buildAllTools();
        String                    sysPrompt = config.getSystemPrompt();
        ActivityListener          listener  = config.getActivityListener();
        MemoryStore               memory    = config.getMemoryStore();

        // Initialise conversation
        List<Map<String, Object>> messages = new ArrayList<>();
        boolean isContinuation = priorMessages != null && !priorMessages.isEmpty();

        if (isContinuation) {
            messages.addAll(priorMessages);
            if (userMessage != null && !userMessage.isBlank()) {
                messages.add(Map.of("role", "user", "content", userMessage));
            } else {
                messages.add(Map.of("role", "user", "content",
                        "You previously worked on this task but were paused. " +
                        "Your full conversation history above shows exactly what you did. " +
                        "Continue from where you left off — do not repeat work already done. Resume now."));
            }
        } else {
            if (userMessage == null || userMessage.isBlank()) {
                return AgentResult.error("userMessage is required for a fresh run");
            }
            messages.add(Map.of("role", "user", "content", userMessage));
        }

        List<AgentResult.ToolCall> toolCalls = new ArrayList<>();
        int totalIn = 0, totalOut = 0, totalCC = 0, totalCR = 0;
        boolean outcomeCheckDone = false;

        int maxIterations = config.getMaxIterations();
        int iterations = 0;

        while (iterations < maxIterations) {
            iterations++;
            log.info("ReactLoop [{}] iteration {}/{}", runId, iterations, maxIterations);
            listener.onIteration(runId, iterations, maxIterations, "LLM call");

            AnthropicClient.ClaudeResponse response = client.send(
                    sysPrompt, messages, tools, config.getApiKey(), config.getModel());

            if (response.hasError()) {
                log.error("ReactLoop [{}] LLM error: {}", runId, response.getError());
                listener.onError(runId, "LLM error: " + response.getError());
                AgentResult r = AgentResult.error("LLM error: " + response.getError(), totalIn, totalOut);
                listener.onComplete(runId, r);
                return r;
            }

            totalIn  += response.getInputTokens();
            totalOut += response.getOutputTokens();
            totalCC  += response.getCacheCreationInputTokens();
            totalCR  += response.getCacheReadInputTokens();

            if (!response.hasToolUse()) {
                // LLM returned plain text — task complete
                String text = response.getTextContent();
                log.info("ReactLoop [{}] completed after {} iterations (stop={})",
                        runId, iterations, response.getStopReason());
                AgentResult r = AgentResult.success(text, iterations, toolCalls,
                        messages, totalIn, totalOut, totalCC, totalCR);
                listener.onComplete(runId, r);
                return r;
            }

            // ── Handle special built-in signals ──────────────────────────────
            List<AnthropicClient.ContentBlock> uses = response.getToolUses();

            // task_complete
            Optional<AnthropicClient.ContentBlock> completeCall = uses.stream()
                    .filter(t -> TASK_COMPLETE_TOOL.equals(t.getToolName())).findFirst();
            if (completeCall.isPresent()) {
                Map<String, Object> ci = completeCall.get().getToolInput();
                String summary = str(ci, "summary", "Task completed");
                String outcome = str(ci, "outcome", "SUCCESS");
                log.info("ReactLoop [{}] task_complete outcome={}", runId, outcome);

                if ("SUCCESS".equals(outcome) && !outcomeCheckDone
                        && expectedOutcome != null && !expectedOutcome.isBlank()) {
                    outcomeCheckDone = true;
                    messages.add(Map.of("role", "assistant", "content", toContentList(response)));
                    Map<String, Object> checkResult = new HashMap<>();
                    checkResult.put("type", "tool_result");
                    checkResult.put("tool_use_id", completeCall.get().getToolId());
                    checkResult.put("content",
                            "Before I accept this completion, please verify your work against the " +
                            "expected outcome below.\n\n## Expected outcome\n" + expectedOutcome +
                            "\n\n## Your reported summary\n" + summary +
                            "\n\nIf all points are satisfied, call task_complete again (outcome=SUCCESS). " +
                            "If something is still missing, continue working and fix it first.");
                    messages.add(Map.of("role", "user", "content", List.of(checkResult)));
                    continue;
                }

                if ("FAILED".equals(outcome)) {
                    AgentResult r = AgentResult.error(summary, totalIn, totalOut);
                    listener.onComplete(runId, r);
                    return r;
                }
                if ("PARTIAL".equals(outcome)) {
                    AgentResult r = AgentResult.partial(summary, iterations, toolCalls,
                            messages, totalIn, totalOut, totalCC, totalCR);
                    listener.onComplete(runId, r);
                    return r;
                }
                AgentResult r = AgentResult.success(summary, iterations, toolCalls,
                        messages, totalIn, totalOut, totalCC, totalCR);
                listener.onComplete(runId, r);
                return r;
            }

            // request_clarification
            if (config.isEnableClarification()) {
                Optional<AnthropicClient.ContentBlock> clarCall = uses.stream()
                        .filter(t -> CLARIFICATION_TOOL.equals(t.getToolName())).findFirst();
                if (clarCall.isPresent()) {
                    String question = str(clarCall.get().getToolInput(), "question", "More information needed.");
                    AgentResult r = AgentResult.needsClarification(question, totalIn, totalOut, totalCC, totalCR);
                    listener.onComplete(runId, r);
                    return r;
                }
            }

            // request_approval
            if (config.isEnableApproval()) {
                Optional<AnthropicClient.ContentBlock> apprCall = uses.stream()
                        .filter(t -> APPROVAL_TOOL.equals(t.getToolName())).findFirst();
                if (apprCall.isPresent()) {
                    Map<String, Object> ai = apprCall.get().getToolInput();
                    String approvalReq = str(ai, "action", "Perform an action")
                            + "\n\nReason: " + str(ai, "reason", "")
                            + (blank(str(ai, "details", "")) ? "" : "\n\nDetails:\n" + ai.get("details"));
                    AgentResult r = AgentResult.needsApproval(approvalReq,
                            apprCall.get().getToolId(), toContentList(response),
                            new ArrayList<>(messages), totalIn, totalOut, totalCC, totalCR);
                    listener.onComplete(runId, r);
                    return r;
                }
            }

            // request_handoff
            if (config.isEnableHandoff()) {
                Optional<AnthropicClient.ContentBlock> handoffCall = uses.stream()
                        .filter(t -> HANDOFF_TOOL.equals(t.getToolName())).findFirst();
                if (handoffCall.isPresent()) {
                    Map<String, Object> hi = handoffCall.get().getToolInput();
                    AgentResult r = AgentResult.handoff(
                            str(hi, "targetRole", "OTHER"),
                            str(hi, "reason", "Handoff requested"),
                            (String) hi.get("context"),
                            str(hi, "reason", ""),
                            iterations, toolCalls, messages,
                            totalIn, totalOut, totalCC, totalCR);
                    listener.onComplete(runId, r);
                    return r;
                }
            }

            // ── Persist full assistant turn ───────────────────────────────────
            messages.add(Map.of("role", "assistant", "content", toContentList(response)));

            // ── Execute all tools in parallel ─────────────────────────────────
            record ToolOutcome(String result, boolean success, long durationMs) {}
            record IndexedOutcome(AnthropicClient.ContentBlock toolUse,
                                   CompletableFuture<ToolOutcome> future) {}

            List<IndexedOutcome> futures = uses.stream().map(toolUse -> {
                CompletableFuture<ToolOutcome> f = CompletableFuture.supplyAsync(() -> {
                    long t0 = System.currentTimeMillis();
                    listener.onToolCall(runId, toolUse.getToolName(), toolUse.getToolInput());
                    try {
                        String result = dispatchTool(toolUse, runId, memory);
                        long dur = System.currentTimeMillis() - t0;
                        listener.onToolResult(runId, toolUse.getToolName(), true, dur);
                        return new ToolOutcome(result, true, dur);
                    } catch (ToolExecutor.ToolExecutionException tee) {
                        long dur = System.currentTimeMillis() - t0;
                        listener.onToolResult(runId, toolUse.getToolName(), false, dur);
                        return new ToolOutcome(tee.getMessage(), false, dur);
                    } catch (Exception e) {
                        long dur = System.currentTimeMillis() - t0;
                        listener.onError(runId, toolUse.getToolName() + " threw: " + e.getMessage());
                        listener.onToolResult(runId, toolUse.getToolName(), false, dur);
                        return new ToolOutcome("Tool execution failed: " + e.getMessage(), false, dur);
                    }
                }, executor);
                return new IndexedOutcome(toolUse, f);
            }).toList();

            List<Map<String, Object>> toolResults = new ArrayList<>();
            for (IndexedOutcome indexed : futures) {
                AnthropicClient.ContentBlock toolUse = indexed.toolUse();
                ToolOutcome outcome;
                try {
                    outcome = indexed.future().get(120, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    indexed.future().cancel(true);
                    listener.onError(runId, toolUse.getToolName() + " timed out after 120s");
                    outcome = new ToolOutcome("Tool timed out after 120 seconds", false, 120_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    outcome = new ToolOutcome("Tool execution was interrupted", false, 0);
                } catch (ExecutionException ee) {
                    String msg = ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage();
                    outcome = new ToolOutcome("Tool execution failed: " + msg, false, 0);
                }

                toolCalls.add(new AgentResult.ToolCall(
                        toolUse.getToolName(), toolUse.getToolInput(),
                        outcome.success(), outcome.durationMs()));

                Map<String, Object> tr = new HashMap<>();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", toolUse.getToolId());
                tr.put("content", formatToolResult(outcome.result()));
                if (!outcome.success()) tr.put("is_error", true);
                toolResults.add(tr);
            }

            messages.add(Map.of("role", "user", "content", toolResults));
            pruneConversationHistory(messages);
        }

        // Max iterations reached
        log.warn("ReactLoop [{}] max iterations ({}) reached", runId, maxIterations);
        listener.onError(runId, "Max iterations (" + maxIterations + ") reached");
        AgentResult r = AgentResult.partial(
                "Task incomplete — max iterations reached",
                maxIterations, toolCalls, messages,
                totalIn, totalOut, totalCC, totalCR);
        listener.onComplete(runId, r);
        return r;
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    private String dispatchTool(AnthropicClient.ContentBlock toolUse,
                                 String runId,
                                 MemoryStore memory) {
        String name  = toolUse.getToolName();
        Map<String, Object> input = toolUse.getToolInput();

        // execute_command — built-in, runs on host
        if (EXECUTE_COMMAND_TOOL.equals(name) && config.isEnableExecuteCommand()) {
            return executeCommand(input);
        }

        // Memory tools
        if (config.isEnableMemoryTools()) {
            if (REMEMBER_TOOL.equals(name)) {
                memory.store("agent", runId,
                        str(input, "key", "memory"),
                        str(input, "content", ""),
                        str(input, "type", "CONTEXT"),
                        intVal(input, "importance", 3));
                return "{\"stored\":true,\"key\":\"" + input.get("key") + "\"}";
            }
            if (RECALL_TOOL.equals(name)) {
                List<MemoryStore.MemoryEntry> found = memory.recallForAgent(runId, str(input, "query", ""));
                return found.isEmpty() ? "No memories found." : formatMemories(found);
            }
            if (WRITE_TASK_MEMORY_TOOL.equals(name)) {
                memory.store("task", runId,
                        str(input, "key", "context"),
                        str(input, "content", ""),
                        str(input, "memoryType", "CONTEXT"),
                        intVal(input, "importance", 3));
                return "{\"stored\":true,\"key\":\"" + input.get("key") + "\"}";
            }
            if (READ_TASK_MEMORY_TOOL.equals(name)) {
                List<MemoryStore.MemoryEntry> found = memory.loadForRun(runId);
                return found.isEmpty() ? "No task context written yet." : formatMemories(found);
            }
        }

        // spawn_task
        if (SPAWN_TASK_TOOL.equals(name) && config.isEnableSpawnTask()) {
            TaskSpawner spawner = config.getTaskSpawner();
            if (spawner != null) return spawner.spawn(input, runId);
            return "{\"error\":\"No TaskSpawner configured\"}";
        }

        // Delegate everything else to the caller-supplied ToolExecutor
        return config.getToolExecutor().execute(name, input, runId);
    }

    // ── execute_command (host, Windows + Linux) ───────────────────────────────

    private String executeCommand(Map<String, Object> params) {
        String command = str(params, "command", "");
        if (command.isBlank()) return "{\"error\":\"command parameter is required\"}";

        String workDir = str(params, "working_directory", null);
        int timeoutSec = Math.min(600, intVal(params, "timeout_seconds", 120));
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        if (workDir != null && !workDir.isBlank()) pb.directory(new File(workDir));
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows ? "NUL" : "/dev/null")));

        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread t1 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stdout.append(line).append("\n");
                } catch (Exception ignored) {}
            });
            Thread t2 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stderr.append(line).append("\n");
                } catch (Exception ignored) {}
            });
            t1.start(); t2.start();
            boolean done = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            t1.join(2000); t2.join(2000);

            if (!done) {
                process.destroyForcibly();
                return "{\"error\":\"Command timed out after " + timeoutSec + " seconds\"}";
            }

            int exitCode = process.exitValue();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exit_code", exitCode);
            if (!stdout.isEmpty()) out.put("stdout", stdout.toString().trim());
            if (!stderr.isEmpty()) out.put("stderr", stderr.toString().trim());
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"Command execution failed: " + e.getMessage() + "\"}";
        }
    }

    // ── Tool list assembly ────────────────────────────────────────────────────

    private List<Map<String, Object>> buildAllTools() {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> all = new ArrayList<>();

        // Caller-provided tools take precedence — add them first
        for (Map<String, Object> t : config.getTools()) {
            String name = (String) t.get("name");
            if (name != null && seen.add(name)) all.add(t);
        }

        // SDK built-ins added only if the caller did not already supply a same-named definition
        if (config.isEnableTaskComplete()  && seen.add(TASK_COMPLETE_TOOL))   all.add(taskCompleteToolDef());
        if (config.isEnableClarification() && seen.add(CLARIFICATION_TOOL))   all.add(clarificationToolDef());
        if (config.isEnableApproval()      && seen.add(APPROVAL_TOOL))        all.add(approvalToolDef());
        if (config.isEnableHandoff()       && seen.add(HANDOFF_TOOL))         all.add(handoffToolDef());
        if (config.isEnableMemoryTools()) {
            for (Map<String, Object> m : memoryToolDefs()) {
                String name = (String) m.get("name");
                if (seen.add(name)) all.add(m);
            }
        }
        if (config.isEnableExecuteCommand() && seen.add(EXECUTE_COMMAND_TOOL)) all.add(executeCommandToolDef());
        if (config.isEnableSpawnTask() && config.getTaskSpawner() != null && seen.add(SPAWN_TASK_TOOL))
            all.add(spawnTaskToolDef());

        return all;
    }

    // ── Built-in tool definitions ─────────────────────────────────────────────

    private static Map<String, Object> taskCompleteToolDef() {
        return Map.of("name", TASK_COMPLETE_TOOL,
            "description", "Call when the task is fully complete. Set outcome=SUCCESS, PARTIAL, or FAILED.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of(
                    "summary", Map.of("type", "string", "description", "What was accomplished"),
                    "outcome", Map.of("type", "string", "enum", List.of("SUCCESS", "PARTIAL", "FAILED"))),
                "required", List.of("summary", "outcome")));
    }

    private static Map<String, Object> clarificationToolDef() {
        return Map.of("name", CLARIFICATION_TOOL,
            "description", "Pause and ask the user a clarifying question when the task is ambiguous.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of("question", Map.of("type", "string")),
                "required", List.of("question")));
    }

    private static Map<String, Object> approvalToolDef() {
        return Map.of("name", APPROVAL_TOOL,
            "description", "Pause and request human approval before performing a risky or irreversible action.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of(
                    "action",  Map.of("type", "string"),
                    "reason",  Map.of("type", "string"),
                    "details", Map.of("type", "string")),
                "required", List.of("action", "reason")));
    }

    private static Map<String, Object> handoffToolDef() {
        return Map.of("name", HANDOFF_TOOL,
            "description", "Signal completion and route to a different agent role.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of(
                    "targetRole", Map.of("type", "string"),
                    "reason",     Map.of("type", "string"),
                    "context",    Map.of("type", "string")),
                "required", List.of("targetRole", "reason")));
    }

    private static List<Map<String, Object>> memoryToolDefs() {
        return List.of(
            Map.of("name", REMEMBER_TOOL,
                "description", "Store a memory that persists across runs.",
                "input_schema", Map.of("type", "object",
                    "properties", Map.of(
                        "key",        Map.of("type", "string"),
                        "content",    Map.of("type", "string"),
                        "type",       Map.of("type", "string", "enum", List.of("CONTEXT","PATTERN","DECISION","OUTCOME")),
                        "importance", Map.of("type", "integer")),
                    "required", List.of("key", "content"))),
            Map.of("name", RECALL_TOOL,
                "description", "Search stored memories.",
                "input_schema", Map.of("type", "object",
                    "properties", Map.of("query", Map.of("type", "string")),
                    "required", List.of("query"))),
            Map.of("name", WRITE_TASK_MEMORY_TOOL,
                "description", "Write context for the next agent in this task.",
                "input_schema", Map.of("type", "object",
                    "properties", Map.of(
                        "key",        Map.of("type", "string"),
                        "content",    Map.of("type", "string"),
                        "memoryType", Map.of("type", "string", "enum", List.of("CONTEXT","DECISION","OUTCOME")),
                        "importance", Map.of("type", "integer")),
                    "required", List.of("key", "content", "memoryType"))),
            Map.of("name", READ_TASK_MEMORY_TOOL,
                "description", "Read context written by previous agents in this task.",
                "input_schema", Map.of("type", "object", "properties", Map.of()))
        );
    }

    private static Map<String, Object> executeCommandToolDef() {
        return Map.of("name", EXECUTE_COMMAND_TOOL,
            "description", "Execute a shell command on the host and return exit_code, stdout, stderr.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of(
                    "command",           Map.of("type", "string"),
                    "working_directory", Map.of("type", "string"),
                    "timeout_seconds",   Map.of("type", "integer")),
                "required", List.of("command")));
    }

    private static Map<String, Object> spawnTaskToolDef() {
        return Map.of("name", SPAWN_TASK_TOOL,
            "description", "Create a new task and assign it to another agent.",
            "input_schema", Map.of("type", "object",
                "properties", Map.of(
                    "assignedAgentId", Map.of("type", "string"),
                    "title",           Map.of("type", "string"),
                    "description",     Map.of("type", "string"),
                    "priority",        Map.of("type", "string", "enum", List.of("LOW","MEDIUM","HIGH","CRITICAL")),
                    "expectedOutcome", Map.of("type", "string")),
                "required", List.of("assignedAgentId", "title", "description")));
    }

    // ── Conversation management ───────────────────────────────────────────────

    /** Convert a ClaudeResponse to the list-of-blocks format required by the Anthropic API. */
    private static List<Map<String, Object>> toContentList(AnthropicClient.ClaudeResponse response) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AnthropicClient.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType())) {
                list.add(Map.of("type", "text", "text", block.getText()));
            } else if ("tool_use".equals(block.getType())) {
                list.add(Map.of("type", "tool_use",
                        "id", block.getToolId(),
                        "name", block.getToolName(),
                        "input", block.getToolInput()));
            }
        }
        return list;
    }

    /**
     * Truncate old conversation rounds to reduce input tokens on every subsequent call.
     * Keeps the last {@value #PRUNE_KEEP_RECENT_ROUNDS} rounds intact; older rounds
     * have tool_result content and assistant reasoning text replaced with stubs.
     */
    @SuppressWarnings("unchecked")
    private void pruneConversationHistory(List<Map<String, Object>> messages) {
        List<Integer> toolResultRounds = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"user".equals(msg.get("role"))) continue;
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;
            boolean hasToolResult = ((List<?>) content).stream()
                    .anyMatch(b -> b instanceof Map && "tool_result".equals(((Map<?,?>) b).get("type")));
            if (hasToolResult) toolResultRounds.add(i);
        }
        if (toolResultRounds.size() <= PRUNE_KEEP_RECENT_ROUNDS) return;

        int pruneUntilIdx = toolResultRounds.get(toolResultRounds.size() - PRUNE_KEEP_RECENT_ROUNDS);
        for (int i = 0; i < pruneUntilIdx; i++) {
            Map<String, Object> msg = messages.get(i);
            Object content = msg.get("content");
            if (!(content instanceof List)) continue;
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

            if ("user".equals(msg.get("role"))) {
                boolean changed = false;
                List<Map<String, Object>> pruned = new ArrayList<>();
                for (Map<String, Object> block : blocks) {
                    if (!"tool_result".equals(block.get("type"))) { pruned.add(block); continue; }
                    Object c = block.get("content");
                    String text = c instanceof String ? (String) c : String.valueOf(c);
                    if (text.length() > PRUNED_RESULT_MAX_CHARS) {
                        Map<String, Object> t = new HashMap<>(block);
                        t.put("content", text.substring(0, PRUNED_RESULT_MAX_CHARS) + " ... [pruned]");
                        pruned.add(t); changed = true;
                    } else { pruned.add(block); }
                }
                if (changed) { Map<String, Object> u = new HashMap<>(msg); u.put("content", pruned); messages.set(i, u); }

            } else if ("assistant".equals(msg.get("role"))) {
                boolean changed = false;
                List<Map<String, Object>> pruned = new ArrayList<>();
                for (Map<String, Object> block : blocks) {
                    if (!"text".equals(block.get("type"))) { pruned.add(block); continue; }
                    String text = (String) block.getOrDefault("text", "");
                    if (text.length() > PRUNED_ASSISTANT_TEXT_MAX_CHARS) {
                        Map<String, Object> t = new HashMap<>(block);
                        t.put("text", text.substring(0, PRUNED_ASSISTANT_TEXT_MAX_CHARS) + " ... [pruned]");
                        pruned.add(t); changed = true;
                    } else { pruned.add(block); }
                }
                if (changed) { Map<String, Object> u = new HashMap<>(msg); u.put("content", pruned); messages.set(i, u); }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatToolResult(String raw) {
        if (raw == null) return "{\"success\":true}";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            if (parsed.containsKey("report")) {
                Object r = parsed.get("report");
                if (r instanceof String rs && rs.length() > MAX_REPORT_CHARS) {
                    parsed.put("report", rs.substring(0, MAX_REPORT_CHARS) + "\n... [truncated]");
                }
                return objectMapper.writeValueAsString(parsed);
            }
        } catch (Exception ignored) {}
        if (raw.length() > MAX_TOOL_RESULT_CHARS) {
            return raw.substring(0, MAX_TOOL_RESULT_CHARS)
                    + "\n... [truncated " + (raw.length() - MAX_TOOL_RESULT_CHARS) + " chars]";
        }
        return raw;
    }

    private static String formatMemories(List<MemoryStore.MemoryEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (MemoryStore.MemoryEntry e : entries) {
            sb.append("[").append(e.type()).append("] ").append(e.key()).append(": ").append(e.content()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String str(Map<String, Object> map, String key, String defaultVal) {
        Object v = map == null ? null : map.get(key);
        return v instanceof String s && !s.isBlank() ? s : defaultVal;
    }

    private static int intVal(Map<String, Object> map, String key, int defaultVal) {
        Object v = map == null ? null : map.get(key);
        return v instanceof Number n ? n.intValue() : defaultVal;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
