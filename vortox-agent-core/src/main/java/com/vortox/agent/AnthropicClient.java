package com.vortox.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Thin Anthropic Messages API client.
 *
 * <ul>
 *   <li>No Spring, no DI framework — instantiate directly.</li>
 *   <li>One shared {@link HttpClient} instance reuses the underlying TCP+TLS connection.</li>
 *   <li>Prompt caching ({@code cache_control: ephemeral}) is applied to the system prompt
 *       and to the last tool definition on every request, matching Vortox behaviour.</li>
 *   <li>Both API-key ({@code x-api-key}) and OAuth ({@code Authorization: Bearer sk-ant-oat…})
 *       are supported.</li>
 *   <li>Transient errors (SSL, 503, 529, timeout) are retried up to three times with
 *       exponential back-off.</li>
 * </ul>
 */
public final class AnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private static final String API_URL           = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String CLAUDE_CODE_SYS   = "You are Claude Code, Anthropic's official CLI for Claude.";
    private static final String CLAUDE_CODE_VER   = "2.1.2";
    private static final String BETA_REGULAR      = "prompt-caching-2024-07-31";
    private static final String BETA_OAUTH        = "claude-code-20250219,oauth-2025-04-20,prompt-caching-2024-07-31";

    private static final int    MAX_RETRIES         = 3;
    private static final long   BASE_RETRY_DELAY_MS = 2_000;
    private static final long   MAX_RETRY_DELAY_MS  = 60_000;
    private static final Set<String> TRANSIENT_KEYWORDS = Set.of(
            "SSLError", "ssl", "SSL", "ConnectionError", "Max retries exceeded",
            "RemoteDisconnected", "IncompleteRead", "BrokenPipeError",
            "timeout", "Timeout", "Connection reset", "503", "529", "overloaded",
            // Rate limiting (HTTP 429). Anthropic returns a Retry-After header we honour below;
            // without this the whole task used to fail hard on the first 429 with no backoff.
            "429", "rate_limit", "rate limit", "Too Many Requests",
            // TLS-layer failures on long streamed/buffered downloads — near-always a transient
            // one-off network hiccup, not an application-level problem.
            "bad_record_mac", "fatal alert", "SSLException", "SocketException",
            "EOFException", "reset by peer", "handshake",
            // Matching is on the exception MESSAGE, not its type, so "EOFException" above never
            // caught the one that actually occurs: java.net.http throws
            // `IOException: EOF reached while reading` when the server closes a response stream
            // early. That is the single most common way a long run dies, and it was being
            // classified permanent — one dropped stream at iteration 30 threw away seven minutes
            // of completed work. HTTP/2 GOAWAY and a plainly-worded closed connection are the same
            // class of thing.
            // "timed out" is not a typo for "timeout" above: java.net.http.HttpTimeoutException's
            // message is literally "request timed out", which the substring "timeout" does not
            // match. That one also appeared in the logs, failing runs that a retry would have saved.
            "EOF reached", "GOAWAY", "connection closed", "Connection closed", "timed out");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final int defaultMaxTokens;

    public AnthropicClient() {
        this(new ObjectMapper(), "claude-sonnet-4-6", 4096);
    }

    public AnthropicClient(ObjectMapper objectMapper, String defaultModel, int defaultMaxTokens) {
        this.objectMapper      = objectMapper;
        this.defaultModel      = defaultModel;
        this.defaultMaxTokens  = defaultMaxTokens;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send a multi-turn conversation with an optional tool list.
     *
     * @param systemPrompt the system prompt
     * @param messages     alternating user/assistant messages in Anthropic wire format
     * @param tools        Claude-format tool definitions (may be null/empty)
     * @param apiKey       Anthropic API key or OAuth token
     * @param model        model ID; if blank the default is used
     * @return parsed {@link ClaudeResponse}
     */
    public ClaudeResponse send(String systemPrompt,
                                List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                String apiKey,
                                String model) {
        String mdl = blank(model) ? defaultModel : model;
        if (blank(apiKey)) return ClaudeResponse.error("API key not configured");
        List<Map<String, Object>> t = (tools != null && !tools.isEmpty()) ? tools : null;
        try {
            return retry(() -> callApi(systemPrompt, messages, t, defaultMaxTokens, apiKey, mdl, 120));
        } catch (Exception e) {
            log.error("send() failed", e);
            return ClaudeResponse.error("Exception: " + e.getMessage());
        }
    }

    /**
     * Streaming send — calls {@code onDelta} for each text chunk, then {@code onComplete} once.
     */
    public void sendStreaming(String systemPrompt,
                               List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools,
                               int maxTokens,
                               String apiKey,
                               String model,
                               int timeoutSeconds,
                               Consumer<String> onDelta,
                               Consumer<ClaudeResponse> onComplete) throws Exception {
        String mdl = blank(model) ? defaultModel : model;
        List<Map<String, Object>> t = (tools != null && !tools.isEmpty()) ? tools : null;
        streamApi(systemPrompt, messages, t, maxTokens, apiKey, mdl, timeoutSeconds, onDelta, onComplete);
    }

    // ── Core HTTP ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ApiCall { ClaudeResponse call() throws Exception; }

    private ClaudeResponse retry(ApiCall fn) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            ClaudeResponse r;
            try {
                r = fn.call();
            } catch (Exception e) {
                // A network/TLS exception thrown mid-request (e.g. bad_record_mac on a large
                // buffered download) never produces a ClaudeResponse at all — without this catch,
                // it would propagate straight out and skip retry entirely, unlike an error that
                // comes back as a normal (non-2xx) response.
                String msg = "Exception: " + e.getMessage();
                if (attempt < MAX_RETRIES && isTransient(msg)) {
                    long delay = retryDelayMs(attempt, msg);
                    log.warn("Transient exception (attempt {}/{}): {}. Retrying in {}ms…",
                            attempt, MAX_RETRIES, msg, delay);
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }
            if (!r.hasError() || !isTransient(r.getError())) return r;
            if (attempt < MAX_RETRIES) {
                long delay = retryDelayMs(attempt, r.getError());
                log.warn("Transient error (attempt {}/{}): {}. Retrying in {}ms…",
                        attempt, MAX_RETRIES, r.getError(), delay);
                Thread.sleep(delay);
            } else {
                log.error("All {} retry attempts failed. Last: {}", MAX_RETRIES, r.getError());
                return r;
            }
        }
        return ClaudeResponse.error("All retry attempts exhausted");
    }

    private ClaudeResponse callApi(String systemPrompt, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, int maxTokens,
                                    String apiKey, String model, int timeoutSeconds) throws Exception {
        String json = objectMapper.writeValueAsString(body(systemPrompt, messages, tools, maxTokens, apiKey, model, false));
        log.debug("Claude API — model:{} messages:{} tools:{}", model, messages.size(), tools != null ? tools.size() : 0);

        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .headers(headers(apiKey, false))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            String err = extractApiError(res.body(), res.statusCode());
            // On a 429, surface the server's Retry-After (seconds) so retry() can honour it instead
            // of using only the fixed exponential backoff.
            if (res.statusCode() == 429) {
                long secs = res.headers().firstValue("retry-after").map(AnthropicClient::parseRetryAfterSeconds).orElse(0L);
                if (secs > 0) err = err + " [retry-after=" + secs + "s]";
            }
            return ClaudeResponse.error(err);
        }
        return parseResponse(res.body());
    }

    @SuppressWarnings("unchecked")
    private void streamApi(String systemPrompt, List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools, int maxTokens,
                            String apiKey, String model, int timeoutSeconds,
                            Consumer<String> onDelta,
                            Consumer<ClaudeResponse> onComplete) throws Exception {
        String json = objectMapper.writeValueAsString(body(systemPrompt, messages, tools, maxTokens, apiKey, model, true));

        HttpResponse<java.util.stream.Stream<String>> res = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(API_URL))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .headers(headers(apiKey, true))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());

        if (res.statusCode() != 200) {
            String err = res.body().collect(Collectors.joining("\n"));
            onComplete.accept(ClaudeResponse.error("API error: " + res.statusCode() + " - " + err));
            return;
        }

        StringBuilder fullText = new StringBuilder();
        int[] in = {0}, out = {0}, cc = {0}, cr = {0};

        res.body().forEach(line -> {
            if (line.isEmpty() || !line.startsWith("data: ")) return;
            String data = line.substring(6);
            if ("[DONE]".equals(data)) return;
            try {
                JsonNode ev = objectMapper.readTree(data);
                switch (ev.path("type").asText()) {
                    case "message_start" -> {
                        JsonNode u = ev.path("message").path("usage");
                        in[0] = u.path("input_tokens").asInt();
                        cc[0] = u.path("cache_creation_input_tokens").asInt();
                        cr[0] = u.path("cache_read_input_tokens").asInt();
                    }
                    case "content_block_delta" -> {
                        JsonNode d = ev.path("delta");
                        if ("text_delta".equals(d.path("type").asText())) {
                            String text = d.path("text").asText();
                            fullText.append(text);
                            onDelta.accept(text);
                        }
                    }
                    case "message_delta" -> out[0] = ev.path("usage").path("output_tokens").asInt();
                }
            } catch (Exception ignored) {}
        });

        ClaudeResponse result = new ClaudeResponse();
        result.setContent(List.of(ContentBlock.text(fullText.toString())));
        result.setStopReason("end_turn");
        result.setInputTokens(in[0]);
        result.setOutputTokens(out[0]);
        result.setCacheCreationInputTokens(cc[0]);
        result.setCacheReadInputTokens(cr[0]);
        onComplete.accept(result);
    }

    // ── Request construction ──────────────────────────────────────────────────

    private Map<String, Object> body(String systemPrompt, List<Map<String, Object>> messages,
                                      List<Map<String, Object>> tools, int maxTokens,
                                      String apiKey, String model, boolean stream) {
        boolean oauth = isOAuth(apiKey);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("model",      model);
        b.put("max_tokens", maxTokens);

        String sysText = oauth ? CLAUDE_CODE_SYS : (systemPrompt != null ? systemPrompt : "");
        b.put("system", List.of(Map.of(
                "type", "text", "text", sysText,
                "cache_control", Map.of("type", "ephemeral"))));

        List<Map<String, Object>> effectiveMessages = (oauth && !blank(systemPrompt))
                ? oauthMessages(messages, systemPrompt) : messages;
        b.put("messages", withCacheBreakpoint(effectiveMessages));

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> cached = new ArrayList<>(tools);
            Map<String, Object> last = new LinkedHashMap<>(cached.get(cached.size() - 1));
            last.put("cache_control", Map.of("type", "ephemeral"));
            cached.set(cached.size() - 1, last);
            b.put("tools", cached);
        }

        if (stream) b.put("stream", true);
        return b;
    }

    private String[] headers(String apiKey, boolean streaming) {
        List<String> h = new ArrayList<>(List.of(
                "content-type", "application/json",
                "anthropic-version", ANTHROPIC_VERSION));
        if (streaming) { h.add("accept"); h.add("text/event-stream"); }
        if (isOAuth(apiKey)) {
            h.addAll(List.of(
                    "Authorization",    "Bearer " + apiKey,
                    "anthropic-beta",   BETA_OAUTH,
                    "user-agent",       "claude-cli/" + CLAUDE_CODE_VER + " (external, cli)",
                    "x-app",            "cli",
                    "anthropic-dangerous-direct-browser-access", "true"));
        } else {
            h.addAll(List.of("x-api-key", apiKey, "anthropic-beta", BETA_REGULAR));
        }
        return h.toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> oauthMessages(List<Map<String, Object>> messages, String systemPrompt) {
        if (messages.isEmpty()) return messages;
        String prefix = "[System Instructions]\n" + systemPrompt + "\n[End System Instructions]\n\n";
        List<Map<String, Object>> result = new ArrayList<>(messages);
        Map<String, Object> first = result.get(0);
        if (!"user".equals(first.get("role"))) return messages;
        Map<String, Object> modified = new LinkedHashMap<>(first);
        Object content = first.get("content");
        if (content instanceof String s) {
            modified.put("content", prefix + s);
        } else if (content instanceof List<?> list) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            boolean done = false;
            for (Object item : list) {
                if (!done && item instanceof Map<?,?> m && "text".equals(m.get("type"))) {
                    Map<String, Object> nb = new LinkedHashMap<>((Map<String, Object>) m);
                    nb.put("text", prefix + m.get("text"));
                    blocks.add(nb);
                    done = true;
                } else {
                    blocks.add((Map<String, Object>) item);
                }
            }
            if (!done) blocks.add(0, Map.of("type", "text", "text", prefix));
            modified.put("content", blocks);
        }
        result.set(0, modified);
        return result;
    }

    /**
     * Marks the last content block of the last message with an ephemeral cache breakpoint.
     * <p>
     * A ReAct loop only ever appends to {@code messages} — each iteration resends the entire
     * growing history. Without this, only the system prompt and tool definitions (fixed-size)
     * are cached, so a 20-iteration run pays full fresh-input price for the accumulated
     * conversation on every single call. Marking the tail of the current messages array means
     * the *next* call — which extends from here — gets a cache hit for everything up to this
     * point, turning most of a long run's history cost into ~90%-cheaper cache reads instead of
     * fresh input tokens. (Anthropic allows up to 4 cache_control breakpoints per request; this
     * is the 3rd, alongside the system prompt and last tool definition.)
     */
    /** Package-visible for tests. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> withCacheBreakpoint(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        int lastIdx = messages.size() - 1;
        Map<String, Object> lastMessage = new LinkedHashMap<>(messages.get(lastIdx));
        Object content = lastMessage.get("content");

        List<Map<String, Object>> blocks;
        if (content instanceof String s) {
            blocks = new ArrayList<>();
            blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", s)));
        } else if (content instanceof List<?> list) {
            blocks = new ArrayList<>();
            for (Object item : list) {
                blocks.add(item instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m)
                        : new LinkedHashMap<>());
            }
        } else {
            return messages; // unknown content shape — leave untouched rather than risk a bad request
        }
        if (blocks.isEmpty()) return messages;

        Map<String, Object> lastBlock = blocks.get(blocks.size() - 1);
        lastBlock.put("cache_control", Map.of("type", "ephemeral"));
        lastMessage.put("content", blocks);

        List<Map<String, Object>> result = new ArrayList<>(messages);
        result.set(lastIdx, lastMessage);
        return result;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private ClaudeResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("error")) {
                JsonNode err = root.path("error");
                String msg = err.isObject() ? err.path("message").asText(err.asText()) : err.asText();
                return ClaudeResponse.error(msg);
            }
            ClaudeResponse r = new ClaudeResponse();
            r.setId(root.path("id").asText());
            r.setModel(root.path("model").asText());
            r.setStopReason(root.path("stop_reason").asText());

            List<ContentBlock> blocks = new ArrayList<>();
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText();
                if ("text".equals(type)) {
                    blocks.add(ContentBlock.text(block.path("text").asText()));
                } else if ("tool_use".equals(type)) {
                    blocks.add(ContentBlock.toolUse(
                            block.path("id").asText(),
                            block.path("name").asText(),
                            objectMapper.convertValue(block.path("input"), Map.class)));
                }
            }
            r.setContent(blocks);

            JsonNode usage = root.path("usage");
            r.setInputTokens(usage.path("input_tokens").asInt());
            r.setOutputTokens(usage.path("output_tokens").asInt());
            r.setCacheCreationInputTokens(usage.path("cache_creation_input_tokens").asInt());
            r.setCacheReadInputTokens(usage.path("cache_read_input_tokens").asInt());
            return r;
        } catch (Exception e) {
            log.error("Response parse error: {}", body, e);
            return ClaudeResponse.error("Parse error: " + e.getMessage());
        }
    }

    private String extractApiError(String body, int status) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String msg = root.path("error").path("message").asText();
            if (!msg.isEmpty()) return "API error: " + status + " - " + msg;
        } catch (Exception ignored) {}
        return "API error: " + status + " - " + (body.length() > 200 ? body.substring(0, 200) : body);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static boolean isOAuth(String key)    { return key != null && key.contains("sk-ant-oat"); }
    private static boolean blank(String s)         { return s == null || s.isBlank(); }
    /** Package-private so the keyword list can be tested directly — it is easy to get subtly wrong. */
    static boolean isTransient(String err) {
        return err != null && TRANSIENT_KEYWORDS.stream().anyMatch(err::contains);
    }

    /**
     * Retry delay for the given attempt: the larger of the exponential backoff and any
     * server-suggested {@code [retry-after=Ns]} marker (parsed from a 429), capped so a huge
     * Retry-After can't stall a worker indefinitely.
     */
    private static long retryDelayMs(int attempt, String errorMessage) {
        long backoff   = BASE_RETRY_DELAY_MS * attempt;
        long suggested = parseRetryAfterMarkerMs(errorMessage);
        return Math.min(Math.max(backoff, suggested), MAX_RETRY_DELAY_MS);
    }

    /** Parse the integer seconds from a {@code Retry-After} header value; 0 if not a plain number. */
    private static long parseRetryAfterSeconds(String header) {
        try {
            return Math.max(0L, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 0L; // HTTP-date form is not handled — caller falls back to exponential backoff
        }
    }

    /** Extract the delay (ms) from a {@code [retry-after=Ns]} marker embedded in an error string. */
    private static long parseRetryAfterMarkerMs(String err) {
        if (err == null) return 0L;
        int i = err.indexOf("[retry-after=");
        if (i < 0) return 0L;
        int end = err.indexOf("s]", i);
        if (end < 0) return 0L;
        try {
            return Long.parseLong(err.substring(i + "[retry-after=".length(), end).trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class ClaudeResponse {
        private String id, model, stopReason, error;
        private List<ContentBlock> content;
        private int inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens;

        public static ClaudeResponse error(String msg) {
            ClaudeResponse r = new ClaudeResponse(); r.setError(msg); return r;
        }

        public boolean hasError()   { return error != null && !error.isEmpty(); }
        public boolean hasToolUse() {
            return content != null && content.stream().anyMatch(b -> "tool_use".equals(b.getType()));
        }
        public List<ContentBlock> getToolUses() {
            return content == null ? List.of()
                    : content.stream().filter(b -> "tool_use".equals(b.getType())).toList();
        }
        public String getTextContent() {
            return content == null ? "" : content.stream()
                    .filter(b -> "text".equals(b.getType()))
                    .map(ContentBlock::getText)
                    .reduce("", (a, b) -> a + b);
        }

        public String getId()                        { return id; }
        public void setId(String id)                 { this.id = id; }
        public String getModel()                     { return model; }
        public void setModel(String model)           { this.model = model; }
        public String getStopReason()                { return stopReason; }
        public void setStopReason(String s)          { this.stopReason = s; }
        public String getError()                     { return error; }
        public void setError(String e)               { this.error = e; }
        public List<ContentBlock> getContent()       { return content; }
        public void setContent(List<ContentBlock> c) { this.content = c; }
        public int getInputTokens()                  { return inputTokens; }
        public void setInputTokens(int n)            { this.inputTokens = n; }
        public int getOutputTokens()                 { return outputTokens; }
        public void setOutputTokens(int n)           { this.outputTokens = n; }
        public int getCacheCreationInputTokens()     { return cacheCreationInputTokens; }
        public void setCacheCreationInputTokens(int n){ this.cacheCreationInputTokens = n; }
        public int getCacheReadInputTokens()         { return cacheReadInputTokens; }
        public void setCacheReadInputTokens(int n)   { this.cacheReadInputTokens = n; }
    }

    public static class ContentBlock {
        private String type, text, toolId, toolName;
        private Map<String, Object> toolInput;

        public static ContentBlock text(String t) {
            ContentBlock b = new ContentBlock(); b.type = "text"; b.text = t; return b;
        }
        public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
            ContentBlock b = new ContentBlock();
            b.type = "tool_use"; b.toolId = id; b.toolName = name; b.toolInput = input; return b;
        }

        public String getType()                         { return type; }
        public String getText()                         { return text; }
        public String getToolId()                       { return toolId; }
        public String getToolName()                     { return toolName; }
        public Map<String, Object> getToolInput()       { return toolInput; }
    }
}
