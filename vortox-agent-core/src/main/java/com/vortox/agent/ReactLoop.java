package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.spi.ActivityListener;
import com.vortox.agent.spi.ControlHook;
import com.vortox.agent.spi.MemoryStore;
import com.vortox.agent.spi.TaskSpawner;
import com.vortox.agent.spi.ToolExecutor;
import com.vortox.agent.spi.ToolGate;
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

    /**
     * Picks what the user actually sees when a run ends via {@code task_complete}.
     *
     * <p>The summary is an argument to a tool call — a status line for the caller. The report the
     * user wants (the number, the table, the caveats) is prose the model wrote in an earlier turn.
     * Using the summary as the reply meant that prose was discarded on every completed run, so an
     * analyst agent that produced a full breakdown delivered one flat sentence instead, and the
     * behaviour read as the assistant becoming vague for no reason.
     *
     * <p>The rule: prefer the prose when there is any, and keep the summary only when it adds
     * something the prose does not already say. A summary that is longer than the prose is treated
     * as the real answer — that is the shape of an agent instructed to put its answer in the summary
     * field, which some prompts do — so this does not regress those.
     *
     * <p>Package-private so the choice can be tested without standing up an LLM call.
     */
    static String chooseReply(String assistantText, String summary) {
        boolean hasText    = assistantText != null && !assistantText.isBlank();
        boolean hasSummary = summary != null && !summary.isBlank();

        if (!hasText) return hasSummary ? summary : "";
        if (!hasSummary) return assistantText;

        String text = assistantText.trim();
        String sum  = summary.trim();

        // The agent put its answer in the summary: honour that rather than replacing it with a
        // shorter lead-in like "Let me run that query for you."
        if (sum.length() > text.length()) return sum;

        // Already said it — appending would only repeat the last line back to the reader.
        if (text.contains(sum)) return text;

        return text;
    }
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
    // Plain-text (non-JSON) tool results were previously capped at 1,500 chars — fine for most
    // skill output, but it silently gutted reference-doc-style skills (e.g. a ~30K-char database
    // schema guide meant to be read BEFORE writing SQL) down to just their first ~20 lines,
    // forcing agents to blindly probe the database's own metadata tables instead — which is both
    // slower and, for skills that export a CSV as a side effect of every query, needlessly
    // generates one throwaway file per exploratory probe. 40,000 comfortably covers that guide
    // with room for it to grow (its own header calls it an "append-only living doc"). What keeps
    // it safe is the character budget below: one 40,000-char result simply spends more of the
    // budget and pushes the pruning boundary later, rather than multiplying against a round count.
    // (This comment used to justify the size by saying prompt caching made resending it cheap.
    // That was true of the intent and false of the behaviour — see the pruning constants below.)
    private static final int MAX_TOOL_RESULT_CHARS         = 40_000;
    private static final int MAX_REPORT_CHARS              = 12_000;

    // Pruning is driven by how much the conversation actually weighs, not by how many rounds it
    // has, and it runs on a hysteresis rather than on every round. Both were needed.
    //
    // **Counting rounds could not be made safe.** A single tool result may be MAX_TOOL_RESULT_CHARS,
    // so "keep the last N rounds" is a promise to keep up to N × 40,000 characters — at N = 16 that
    // is more than a 200K-token window holds. Any N large enough to be useful for small results is
    // unsafe for large ones. A character budget is the same rule stated in the unit that matters,
    // and it self-adjusts: many small rounds survive, a few enormous ones do not.
    //
    // **Pruning every round was fighting the prompt cache.** The cache breakpoint sits on the last
    // message, so the cached prefix is the whole history; rewriting any earlier message invalidates
    // it from that point. The old rule stubbed one more round on every single iteration past the
    // fifth, so from round six onward each call changed the prefix, missed the cache for everything
    // after the change, and paid the cache-write surcharge on content the next round would
    // invalidate again. The comment above — that caching makes resending large results cheap — was
    // true of the design and false in practice.
    //
    // So: touch nothing until the conversation crosses HIGH, then prune back to LOW in one pass.
    // Between crossings the prefix is byte-identical and the cache does what it was added to do.
    // Most runs never reach HIGH at all and are now never mutated.
    private static final int PRUNE_HIGH_WATER_CHARS        = 320_000;
    private static final int PRUNE_LOW_WATER_CHARS         = 160_000;
    /** Never stub the most recent rounds, however large — an agent must see what it just did. */
    private static final int PRUNE_ALWAYS_KEEP_ROUNDS      = 3;
    // Room for a usable gist rather than a fragment. At 400 a stubbed file listing or test output
    // was one truncated line, which tells a later iteration that something happened and nothing
    // about what.
    private static final int PRUNED_RESULT_MAX_CHARS       = 1_500;
    private static final int PRUNED_ASSISTANT_TEXT_MAX_CHARS = 600;
    // Stubbing results and text alone did not keep the hysteresis. A stubbed round still carries
    // its tool_use input in full — write_file/edit_file content, whole commands — and a long run
    // accumulates enough of that residue that the stubbed history alone stayed over HIGH. Every
    // later iteration then re-entered the prune, moved the boundary one round, rewrote a message
    // deep in the history and missed the cache for everything after it: measured as 25–54K
    // cache-write tokens per iteration instead of ~1K, the bulk of all cache writes. So stubbed
    // rounds also get their input values cut, and if the stubbed history still weighs more than
    // PRUNE_RESIDUE_MAX_CHARS the oldest whole rounds are dropped. After a prune the conversation
    // is at most LOW + RESIDUE (+ the first message), which leaves real headroom under HIGH.
    private static final int PRUNED_INPUT_VALUE_MAX_CHARS  = 300;
    private static final int PRUNE_RESIDUE_MAX_CHARS       = 60_000;
    private static final String PRUNED_MARKER              = " ... [pruned]";
    static final String DROPPED_ROUNDS_NOTE =
            "[Note: earlier rounds of this run were dropped to fit the context window. "
            + "Rely on the current state of files and the recent rounds below.]";

    /** A task title is free text; keep the log line readable rather than letting one run own it. */
    private static final int RUN_LABEL_MAX_CHARS = 70;

    private final AgentConfig config;
    private final LlmClient client;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public ReactLoop(AgentConfig config) {
        this(config,
             config.getLlmClient() != null ? config.getLlmClient()
                 : new AnthropicClient(new ObjectMapper(), config.getModel(), config.getMaxTokens()),
             ForkJoinPool.commonPool());
    }

    /** Kept for callers (e.g. AgentRunner) that inject an AnthropicClient Spring bean. */
    public ReactLoop(AgentConfig config, LlmClient client, Executor executor) {
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
     * Run with a human-readable label for the work — in Vortox, the task title. The label only
     * identifies this run in the log; it is never sent to the LLM.
     */
    public AgentResult run(String userMessage, String runId, String expectedOutcome,
                           List<Map<String, Object>> priorMessages, String runLabel) {
        return runInternal(userMessage, runId, expectedOutcome, priorMessages, runLabel);
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
        return resumeAfterApproval(runId, conversationSnapshot, assistantContent, toolUseId,
                decisionMessage, null);
    }

    /** As {@link #resumeAfterApproval}, with a human-readable label for the log. */
    public AgentResult resumeAfterApproval(String runId,
                                            List<Map<String, Object>> conversationSnapshot,
                                            List<Map<String, Object>> assistantContent,
                                            String toolUseId,
                                            String decisionMessage,
                                            String runLabel) {
        log.info("ReactLoop [{}] resuming after approval", runTag(runId, runLabel));
        List<Map<String, Object>> messages = new ArrayList<>(sanitizeMessages(conversationSnapshot));
        messages.add(Map.of("role", "assistant", "content", assistantContent));
        messages.add(Map.of("role", "user", "content",
                approvalToolResults(assistantContent, toolUseId, decisionMessage)));

        return runInternal(null, runId, null, messages, runLabel);
    }

    /**
     * One {@code tool_result} block for every {@code tool_use} in the paused assistant turn.
     *
     * <p><strong>Why not just the gated one.</strong> The Messages API requires a result for each
     * tool_use in the preceding assistant turn and rejects the request otherwise. This path used to
     * answer only the id that paused the run, which is correct exactly when the model asked for one
     * thing and nothing else — and a resumed run would fail on {@code tool_use} ids without
     * {@code tool_result} the moment the model called two tools at once. That was reachable before
     * (the model could pair {@code request_approval} with another call) and is routine now that a
     * host policy can hold any call: a guarded agent that reads a file and writes one in the same
     * turn pauses on the write with the read still outstanding.
     *
     * <p>The others are answered honestly rather than fabricated: they did not run, and the model is
     * told to call them again if it still needs them.
     */
    static List<Map<String, Object>> approvalToolResults(List<Map<String, Object>> assistantContent,
                                                          String gatedToolUseId,
                                                          String decisionMessage) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (assistantContent != null) {
            for (Map<String, Object> block : assistantContent) {
                if (block == null || !"tool_use".equals(block.get("type"))) continue;
                Object id = block.get("id");
                if (!(id instanceof String useId) || useId.isBlank()) continue;

                Map<String, Object> result = new HashMap<>();
                result.put("type", "tool_result");
                result.put("tool_use_id", useId);
                result.put("content", useId.equals(gatedToolUseId)
                        ? decisionMessage
                        : "Not executed — the turn was paused while another call in it waited for "
                          + "approval. Call this again if you still need it.");
                results.add(result);
            }
        }

        // A snapshot with no recognisable tool_use block still has to answer the id that paused the
        // run, or the resume is malformed in the other direction.
        if (results.stream().noneMatch(r -> gatedToolUseId != null
                && gatedToolUseId.equals(r.get("tool_use_id")))) {
            Map<String, Object> result = new HashMap<>();
            result.put("type", "tool_result");
            result.put("tool_use_id", gatedToolUseId);
            result.put("content", decisionMessage);
            results.add(result);
        }
        return results;
    }

    /** The stop reason for a reply that ran out of output tokens (OpenAI's "length" maps to it). */
    static final String MAX_TOKENS_STOP = "max_tokens";

    static final String TRUNCATED_TOOL_CALL =
            "Not executed — your reply hit the output token limit and was cut off while writing "
            + "this call, so its arguments are incomplete. Do not repeat it at the same size: it "
            + "will be cut off again. Split the work into smaller calls — for a large file, "
            + "file_write the first part (a few hundred lines at most) and file_append the rest "
            + "in further calls of similar size.";

    static final String TRUNCATED_TEXT =
            "Your previous reply hit the output token limit and was cut off. Continue from where "
            + "it stopped, and keep each reply shorter. If you were about to write a large file, "
            + "write it in several smaller file_write / file_append calls.";

    /**
     * What to send back after a reply that ran out of output tokens.
     *
     * <p>Every {@code tool_use} in the cut-off turn is answered with an error, because the Messages
     * API rejects a request that leaves one unanswered — and none of them is run, because the last
     * one is certainly incomplete and there is no reliable way to tell which of the others were
     * finished. A turn with no tool call gets a plain instruction to continue instead.
     */
    static List<Map<String, Object>> truncatedTurnReply(List<Map<String, Object>> assistantContent) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (assistantContent != null) {
            for (Map<String, Object> block : assistantContent) {
                if (block == null || !"tool_use".equals(block.get("type"))) continue;
                Object id = block.get("id");
                if (!(id instanceof String useId) || useId.isBlank()) continue;
                Map<String, Object> result = new HashMap<>();
                result.put("type", "tool_result");
                result.put("tool_use_id", useId);
                result.put("content", TRUNCATED_TOOL_CALL);
                result.put("is_error", true);
                results.add(result);
            }
        }
        if (results.isEmpty()) {
            return List.of(Map.of("type", "text", "text", TRUNCATED_TEXT));
        }
        return results;
    }

    /**
     * Adds the "carry on from here" instruction to a resumed conversation without creating two
     * {@code user} turns in a row.
     *
     * <p><strong>Why this is not just an {@code add}.</strong> A snapshot taken when the loop ran out
     * of iterations ends with the {@code user} message carrying that round's {@code tool_result}
     * blocks — the loop appends the results and then re-checks the budget. Appending a fresh
     * {@code user} message after it produces two consecutive user turns, and whether the Messages API
     * merges those or rejects them with {@code roles must alternate} is not something to leave to
     * chance in the one code path that only runs after a run has already gone wrong. Folding the
     * instruction into that final turn as an extra {@code text} block is well-formed either way: a
     * user turn may carry {@code tool_result} blocks followed by text.
     *
     * <p>This is what made {@link #resume} usable for the case its own javadoc names. Only the
     * approval gate had used it, and that path happens to append an {@code assistant} turn first, so
     * it never hit this.
     */
    static void appendContinuationInstruction(List<Map<String, Object>> messages, String instruction) {
        int lastIndex = messages.size() - 1;
        Map<String, Object> last = lastIndex >= 0 ? messages.get(lastIndex) : null;

        if (last != null && "user".equals(last.get("role")) && last.get("content") instanceof List<?> blocks) {
            List<Object> merged = new ArrayList<>(blocks);
            merged.add(Map.of("type", "text", "text", instruction));
            messages.set(lastIndex, Map.of("role", "user", "content", merged));
            return;
        }
        if (last != null && "user".equals(last.get("role")) && last.get("content") instanceof String text) {
            messages.set(lastIndex, Map.of("role", "user", "content", text + "\n\n" + instruction));
            return;
        }
        messages.add(Map.of("role", "user", "content", instruction));
    }

    /**
     * The Anthropic Messages API rejects any message object with keys other than "role"/"content" —
     * a resumed/persisted conversation snapshot may carry extra metadata (e.g. a "timestamp" added
     * by an unrelated persistence path) that was never part of what the API itself returned. Strip
     * anything but role/content before such a snapshot re-enters an API-bound message list.
     */
    private static List<Map<String, Object>> sanitizeMessages(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        List<Map<String, Object>> clean = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            if (m == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", m.get("role"));
            entry.put("content", m.get("content"));
            clean.add(entry);
        }
        return clean;
    }

    // ── Core loop ─────────────────────────────────────────────────────────────

    /**
     * How a run identifies itself in the log. A run id on its own is an opaque UUID: with several
     * agents working in parallel, a reader cannot tell which agent is talking to the LLM, nor about
     * what. Naming the agent and the work as well makes a single log line stand on its own.
     *
     * <p>Package-private so the format can be tested without standing up an LLM call.
     */
    String runTag(String runId, String runLabel) {
        StringBuilder sb = new StringBuilder();
        String agentId = config.getAgentId();
        if (agentId != null && !agentId.isBlank()) sb.append(agentId).append(" · ");
        sb.append(runId);
        if (runLabel != null && !runLabel.isBlank()) {
            String label = runLabel.strip().replaceAll("\\s+", " ");
            if (label.length() > RUN_LABEL_MAX_CHARS) {
                label = label.substring(0, RUN_LABEL_MAX_CHARS - 1).strip() + "…";
            }
            sb.append(" · '").append(label).append("'");
        }
        return sb.toString();
    }

    private AgentResult runInternal(String userMessage,
                                    String runId,
                                    String expectedOutcome,
                                    List<Map<String, Object>> priorMessages,
                                    String runLabel) {

        // Prefixes every log line this run emits, so one line names the agent, the task id and the
        // task itself — see runTag.
        final String tag = runTag(runId, runLabel);

        List<Map<String, Object>> tools    = buildAllTools();
        String                    sysPrompt = config.getSystemPrompt();
        ActivityListener          listener  = config.getActivityListener();
        ControlHook               control   = config.getControlHook() != null ? config.getControlHook() : ControlHook.NOOP;
        MemoryStore               memory    = config.getMemoryStore();

        // Initialise conversation
        List<Map<String, Object>> messages = new ArrayList<>();
        boolean isContinuation = priorMessages != null && !priorMessages.isEmpty();

        if (isContinuation) {
            messages.addAll(sanitizeMessages(priorMessages));
            String continueWith = userMessage != null && !userMessage.isBlank()
                    ? userMessage
                    : "You previously worked on this task but were paused. "
                    + "Your full conversation history above shows exactly what you did. "
                    + "Continue from where you left off — do not repeat work already done. Resume now.";
            appendContinuationInstruction(messages, continueWith);
        } else {
            if (userMessage == null || userMessage.isBlank()) {
                return AgentResult.error("userMessage is required for a fresh run");
            }
            messages.add(Map.of("role", "user", "content", userMessage));
        }

        List<AgentResult.ToolCall> toolCalls = new ArrayList<>();
        int totalIn = 0, totalOut = 0, totalCC = 0, totalCR = 0;
        boolean outcomeCheckDone = false;
        /** The last non-blank prose the model produced, in any turn — see chooseReply. */
        String lastAssistantText = null;

        int maxIterations = config.getMaxIterations();
        int iterations = 0;

        while (iterations < maxIterations) {
            iterations++;

            // Human control checkpoint: may block (pause), splice guidance into `messages`,
            // or return false to cancel. Executor-agnostic — see ControlHook.
            if (!control.beforeIteration(runId, iterations, messages)) {
                log.info("ReactLoop [{}] cancelled by control channel at iteration {}", tag, iterations);
                AgentResult r = AgentResult.cancelled(
                        "Run cancelled by operator at iteration " + iterations,
                        iterations, toolCalls, messages, totalIn, totalOut, totalCC, totalCR);
                listener.onComplete(runId, r);
                return r;
            }

            log.info("ReactLoop [{}] iteration {}/{}", tag, iterations, maxIterations);
            listener.onIteration(runId, iterations, maxIterations, "LLM call");

            AnthropicClient.ClaudeResponse response = client.send(
                    sysPrompt, messages, tools, config.getApiKey(), config.getModel());

            if (response.hasError()) {
                log.error("ReactLoop [{}] LLM error: {}", tag, response.getError());
                listener.onError(runId, "LLM error: " + response.getError());
                AgentResult r = AgentResult.error("LLM error: " + response.getError(), totalIn, totalOut);
                listener.onComplete(runId, r);
                return r;
            }

            totalIn  += response.getInputTokens();
            totalOut += response.getOutputTokens();
            totalCC  += response.getCacheCreationInputTokens();
            totalCR  += response.getCacheReadInputTokens();
            listener.onTokens(runId, iterations, totalIn, totalOut);

            // What actually went over the wire this turn. Tagged like every other line, so a reader
            // can follow one agent's conversation about one task through a log holding many.
            log.debug("ReactLoop [{}] LLM turn {} — model={} sent {} message(s), {} tool(s); "
                            + "stop={} tokens in/out {}/{} (cache write/read {}/{})",
                    tag, iterations, config.getModel(), messages.size(),
                    tools != null ? tools.size() : 0, response.getStopReason(),
                    response.getInputTokens(), response.getOutputTokens(),
                    response.getCacheCreationInputTokens(), response.getCacheReadInputTokens());

            // Keep the most recent prose the model wrote. A model that works for several turns and
            // then signals completion typically writes its actual answer — the formatted report,
            // the table — in an earlier turn, and gives task_complete a one-line summary. Taking the
            // summary as the reply threw that away: runs spending well over a thousand output
            // tokens were delivering a single sentence to the user. See chooseReply below.
            String iterationText = response.getTextContent();
            if (iterationText != null && !iterationText.isBlank()) {
                lastAssistantText = iterationText.trim();
            }

            // The reply ran out of output tokens. Whatever it holds is incomplete: a tool call cut
            // mid-input arrives with its arguments missing, and a text-only reply is half an
            // answer. Running the call made the model retry the same oversized write blind — a
            // "$.content is missing" validation error says nothing about why — and accepting the
            // text completed tasks that had not finished. Tell the model what happened instead.
            if (MAX_TOKENS_STOP.equals(response.getStopReason())) {
                List<Map<String, Object>> assistantContent = toContentList(response);
                log.warn("ReactLoop [{}] reply cut off at the output token limit on iteration {} "
                                + "({} output tokens, {} tool call(s) discarded)",
                        tag, iterations, response.getOutputTokens(), response.getToolUses().size());
                for (AnthropicClient.ContentBlock cut : response.getToolUses()) {
                    toolCalls.add(new AgentResult.ToolCall(cut.getToolName(), cut.getToolInput(),
                            TRUNCATED_TOOL_CALL, false, 0));
                }
                messages.add(Map.of("role", "assistant", "content", assistantContent));
                messages.add(Map.of("role", "user", "content", truncatedTurnReply(assistantContent)));
                pruneConversationHistory(messages);
                continue;
            }

            if (!response.hasToolUse()) {
                // LLM returned plain text — task complete
                String text = response.getTextContent();
                log.info("ReactLoop [{}] completed after {} iterations (stop={})",
                        tag, iterations, response.getStopReason());
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
                log.info("ReactLoop [{}] task_complete outcome={}", tag, outcome);

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
                String reply = chooseReply(lastAssistantText, summary);
                if ("PARTIAL".equals(outcome)) {
                    AgentResult r = AgentResult.partial(reply, iterations, toolCalls,
                            messages, totalIn, totalOut, totalCC, totalCR);
                    listener.onComplete(runId, r);
                    return r;
                }
                AgentResult r = AgentResult.success(reply, iterations, toolCalls,
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

            // ── Host tool gate ────────────────────────────────────────────────
            // Consulted before the assistant turn is persisted, so the snapshot handed back has the
            // same shape as an agent-initiated approval: messages without the assistant turn, plus
            // that turn separately. Checked before dispatch because the block below executes every
            // tool in the turn in parallel — a refusal that arrives from inside the executor arrives
            // after the write it was meant to stop.
            ToolGate gate = config.getToolGate();
            if (gate != null && gate != ToolGate.OPEN) {
                for (AnthropicClient.ContentBlock use : uses) {
                    String request;
                    try {
                        request = gate.approvalRequestFor(use.getToolName(), use.getToolInput());
                    } catch (Exception e) {
                        // A gate that cannot answer is not a licence to proceed: an unreadable
                        // policy has to stop the call, the same way an unreadable approval gate does.
                        log.error("ReactLoop [{}] tool gate threw for {} — pausing rather than "
                                + "running ungated: {}", runTag(runId, runLabel), use.getToolName(),
                                e.getMessage(), e);
                        request = "The permission policy for this run could not be evaluated, so `"
                                + use.getToolName() + "` was held.\n\nReason: " + e.getMessage()
                                + "\n\nApprove to run it anyway; reject to stop the task.";
                    }
                    if (request != null) {
                        log.info("ReactLoop [{}] tool gate held {} for approval",
                                runTag(runId, runLabel), use.getToolName());
                        AgentResult r = AgentResult.needsApproval(request,
                                use.getToolId(), toContentList(response),
                                new ArrayList<>(messages), totalIn, totalOut, totalCC, totalCR);
                        listener.onComplete(runId, r);
                        return r;
                    }
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
                        toolUse.getToolName(), toolUse.getToolInput(), outcome.result(),
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
        log.warn("ReactLoop [{}] max iterations ({}) reached", tag, maxIterations);
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
     * Shrink old rounds once the conversation gets heavy, and otherwise leave it entirely alone.
     *
     * <p>Does nothing at all below {@link #PRUNE_HIGH_WATER_CHARS}: no mutation means a
     * byte-identical prefix, which is what the prompt cache needs to hit. Above it, walks back from
     * the newest round keeping full content until {@link #PRUNE_LOW_WATER_CHARS} is spent and stubs
     * everything older in one pass — so the next several rounds fit under HIGH again and change
     * nothing. The last {@link #PRUNE_ALWAYS_KEEP_ROUNDS} rounds are kept whatever they weigh.
     */
    /** Static and package-visible: it reads no instance state, and this is worth testing directly. */
    @SuppressWarnings("unchecked")
    static void pruneConversationHistory(List<Map<String, Object>> messages) {
        if (charsOf(messages, 0, messages.size()) <= PRUNE_HIGH_WATER_CHARS) return;

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
        if (toolResultRounds.size() <= PRUNE_ALWAYS_KEEP_ROUNDS) return;

        // Walk back from the newest round, spending the low-water budget. The boundary lands where
        // the budget runs out, never later than the always-keep rounds.
        int newest = toolResultRounds.size() - 1;
        int keepFrom = newest - (PRUNE_ALWAYS_KEEP_ROUNDS - 1);
        long spent = charsOf(messages, toolResultRounds.get(keepFrom), messages.size());

        while (keepFrom > 0) {
            int candidate = keepFrom - 1;
            long extra = charsOf(messages, toolResultRounds.get(candidate), toolResultRounds.get(keepFrom));
            if (spent + extra > PRUNE_LOW_WATER_CHARS) break;
            spent += extra;
            keepFrom = candidate;
        }
        if (keepFrom == 0) return;

        int pruneUntilIdx = toolResultRounds.get(keepFrom);
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
                        t.put("content", text.substring(0, PRUNED_RESULT_MAX_CHARS) + PRUNED_MARKER);
                        pruned.add(t); changed = true;
                    } else { pruned.add(block); }
                }
                if (changed) { Map<String, Object> u = new HashMap<>(msg); u.put("content", pruned); messages.set(i, u); }

            } else if ("assistant".equals(msg.get("role"))) {
                boolean changed = false;
                List<Map<String, Object>> pruned = new ArrayList<>();
                for (Map<String, Object> block : blocks) {
                    if ("tool_use".equals(block.get("type")) && block.get("input") instanceof Map) {
                        Map<String, Object> input = stubInputValues((Map<String, Object>) block.get("input"));
                        if (input != null) {
                            Map<String, Object> t = new HashMap<>(block);
                            t.put("input", input);
                            pruned.add(t); changed = true;
                        } else { pruned.add(block); }
                        continue;
                    }
                    if (!"text".equals(block.get("type"))) { pruned.add(block); continue; }
                    String text = (String) block.getOrDefault("text", "");
                    if (text.length() > PRUNED_ASSISTANT_TEXT_MAX_CHARS) {
                        Map<String, Object> t = new HashMap<>(block);
                        t.put("text", text.substring(0, PRUNED_ASSISTANT_TEXT_MAX_CHARS) + PRUNED_MARKER);
                        pruned.add(t); changed = true;
                    } else { pruned.add(block); }
                }
                if (changed) { Map<String, Object> u = new HashMap<>(msg); u.put("content", pruned); messages.set(i, u); }
            }
        }

        dropOldestRoundsOverResidue(messages, pruneUntilIdx);
    }

    /**
     * A copy of a stubbed round's tool input with its long string values cut, or {@code null} when
     * nothing needed cutting. The input stays an object — the API requires one — with its keys intact,
     * so a later iteration can still tell which file was written or which command ran.
     */
    private static Map<String, Object> stubInputValues(Map<String, Object> input) {
        Map<String, Object> out = null;
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (!(e.getValue() instanceof String s)) continue;
            if (s.length() <= PRUNED_INPUT_VALUE_MAX_CHARS + PRUNED_MARKER.length()) continue;
            if (out == null) out = new LinkedHashMap<>(input);
            out.put(e.getKey(), s.substring(0, PRUNED_INPUT_VALUE_MAX_CHARS) + PRUNED_MARKER);
        }
        return out;
    }

    /**
     * If the stubbed part of the history ({@code [1, pruneUntilIdx)}) still weighs more than
     * {@link #PRUNE_RESIDUE_MAX_CHARS}, drop its oldest whole rounds until it fits.
     *
     * <p>The cut always lands just before an assistant message that follows a user message, so the
     * roles still alternate and no tool_use is separated from its tool_result. The first message —
     * the task itself — is always kept, and gets a one-time note that history was dropped.
     */
    @SuppressWarnings("unchecked")
    private static void dropOldestRoundsOverResidue(List<Map<String, Object>> messages, int pruneUntilIdx) {
        if (charsOf(messages, 1, pruneUntilIdx) <= PRUNE_RESIDUE_MAX_CHARS) return;

        int cut = -1;
        for (int j = 2; j < pruneUntilIdx; j++) {
            if (!"assistant".equals(messages.get(j).get("role"))) continue;
            if (!"user".equals(messages.get(j - 1).get("role"))) continue;
            cut = j;
            if (charsOf(messages, j, pruneUntilIdx) <= PRUNE_RESIDUE_MAX_CHARS) break;
        }
        if (cut < 2) return;

        messages.subList(1, cut).clear();

        Map<String, Object> first = messages.get(0);
        Object content = first.get("content");
        if (content instanceof String s) {
            if (!s.contains(DROPPED_ROUNDS_NOTE)) {
                Map<String, Object> f = new HashMap<>(first);
                f.put("content", s + "\n\n" + DROPPED_ROUNDS_NOTE);
                messages.set(0, f);
            }
        } else if (content instanceof List<?> list) {
            boolean noted = list.stream().anyMatch(b -> b instanceof Map
                    && DROPPED_ROUNDS_NOTE.equals(((Map<?, ?>) b).get("text")));
            if (!noted) {
                List<Object> withNote = new ArrayList<>((List<Object>) list);
                withNote.add(Map.of("type", "text", "text", DROPPED_ROUNDS_NOTE));
                Map<String, Object> f = new HashMap<>(first);
                f.put("content", withNote);
                messages.set(0, f);
            }
        }
    }

    /**
     * Roughly what messages {@code [from, to)} weigh, in characters.
     *
     * <p>Characters rather than tokens on purpose: a tokeniser here would cost more than the
     * decision is worth, and every threshold this feeds is a rule of thumb about a budget, not an
     * accounting of one. Roughly four characters to the token is close enough to size a window.
     */
    @SuppressWarnings("unchecked")
    static long charsOf(List<Map<String, Object>> messages, int from, int to) {
        long total = 0;
        for (int i = Math.max(0, from); i < Math.min(to, messages.size()); i++) {
            Object content = messages.get(i).get("content");
            if (content instanceof String s) { total += s.length(); continue; }
            if (!(content instanceof List)) continue;
            for (Object block : (List<Object>) content) {
                if (!(block instanceof Map)) continue;
                Map<String, Object> b = (Map<String, Object>) block;
                Object text = b.get("text");
                if (text instanceof String s) total += s.length();
                Object inner = b.get("content");
                if (inner instanceof String s) total += s.length();
                else if (inner != null) total += String.valueOf(inner).length();
                Object input = b.get("input");
                if (input != null) total += String.valueOf(input).length();
            }
        }
        return total;
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
