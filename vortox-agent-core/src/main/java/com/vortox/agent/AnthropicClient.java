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
            "EOF reached", "GOAWAY", "connection closed", "Connection closed", "timed out",
            // Could not reach the host at all: DNS or routing. A ConnectException from java.net.http
            // carries no message, so these only match now that describe() names the exception and
            // its causes (2026-09-30: two DNS blips inside Docker, "Exception: null", each failed a
            // whole benchmark run outright — one of them 46 minutes in).
            "ConnectException", "UnresolvedAddressException", "UnknownHostException",
            "NoRouteToHostException", "HttpConnectTimeoutException", "Network is unreachable");

    /**
     * A failure as text that says what it was: each exception's simple class name and message, down
     * the cause chain. {@code "Exception: " + e.getMessage()} turned a DNS failure into
     * "Exception: null", which neither this client's retry nor the platform's could recognise.
     */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        int open = 0;
        Throwable t = e;
        for (int depth = 0; t != null && depth < 4; depth++) {
            if (depth > 0) { sb.append(" (caused by "); open++; }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null && !t.getMessage().isBlank()) sb.append(": ").append(t.getMessage());
            Throwable next = t.getCause();
            t = next == t ? null : next;
        }
        sb.append(")".repeat(open));
        return sb.toString();
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * How long a model call may go without receiving anything before the attempt is abandoned and
     * retried. The tool loop's calls are streamed, and the API sends {@code ping} events while it
     * works, so silence this long means the connection has stalled, not that the model is thinking.
     * Before, a call waited for its whole reply under a timeout sized for the longest legitimate
     * reply (~10 min at 16k tokens), three times over: tasks 717 and 718 sat silent 31 minutes
     * (2026-10-12) before the run failed and the platform restarted it from iteration 1.
     *
     * <p>{@code VORTOX_LLM_IDLE_TIMEOUT_SECONDS} overrides it; {@code 0} turns streaming off and
     * restores the buffered call.
     */
    static final long DEFAULT_IDLE_TIMEOUT_MS = idleTimeoutFromEnv(90);

    private final ObjectMapper objectMapper;
    private final String defaultModel;
    private final int defaultMaxTokens;
    private final String apiUrl;
    private final long idleTimeoutMs;
    /**
     * {@code output_config.effort} for every request, or null to send none (the model's default —
     * {@code high} on Claude Opus 5, which thinks by default). Effort is how much a model thinks
     * and writes per reply; see {@link #withEffort}.
     */
    private final String effort;

    public AnthropicClient() {
        this(new ObjectMapper(), "claude-sonnet-4-6", 4096);
    }

    public AnthropicClient(ObjectMapper objectMapper, String defaultModel, int defaultMaxTokens) {
        this(objectMapper, defaultModel, defaultMaxTokens, API_URL, DEFAULT_IDLE_TIMEOUT_MS);
    }

    /** The endpoint and idle window are injectable for tests; 0 ms means buffered, not streamed. */
    AnthropicClient(ObjectMapper objectMapper, String defaultModel, int defaultMaxTokens,
                    String apiUrl, long idleTimeoutMs) {
        this(objectMapper, defaultModel, defaultMaxTokens, apiUrl, idleTimeoutMs, null);
    }

    private AnthropicClient(ObjectMapper objectMapper, String defaultModel, int defaultMaxTokens,
                            String apiUrl, long idleTimeoutMs, String effort) {
        this.objectMapper      = objectMapper;
        this.defaultModel      = defaultModel;
        this.defaultMaxTokens  = defaultMaxTokens;
        this.apiUrl            = apiUrl;
        this.idleTimeoutMs     = Math.max(0, idleTimeoutMs);
        this.effort            = blank(effort) ? null : effort.trim().toLowerCase();
    }

    /**
     * The same client, asking for {@code effort} ({@code low}, {@code medium}, {@code high},
     * {@code xhigh}, {@code max}) on every request; null or blank sends none. Lower effort means
     * less thinking, fewer and more consolidated tool calls, and faster replies — right for a
     * sub-agent that only looks things up.
     */
    public AnthropicClient withEffort(String effort) {
        return new AnthropicClient(objectMapper, defaultModel, defaultMaxTokens, apiUrl, idleTimeoutMs, effort);
    }

    public String getEffort() { return effort; }

    private static long idleTimeoutFromEnv(long defaultSeconds) {
        String v = System.getenv("VORTOX_LLM_IDLE_TIMEOUT_SECONDS");
        if (v == null || v.isBlank()) return defaultSeconds * 1000L;
        try {
            return Math.max(0L, Long.parseLong(v.trim())) * 1000L;
        } catch (NumberFormatException e) {
            return defaultSeconds * 1000L;
        }
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
            int ceiling = requestTimeoutSeconds(defaultMaxTokens);
            List<Map<String, Object>> sent = withoutThinkingReplay.get() ? withoutThinking(messages) : messages;
            ApiCall call = () -> idleTimeoutMs > 0
                    ? callApiStreamed(systemPrompt, sent, t, defaultMaxTokens, apiKey, mdl, ceiling)
                    : callApi(systemPrompt, sent, t, defaultMaxTokens, apiKey, mdl, ceiling);
            ClaudeResponse r = retry(call);

            // Two settings a model may refuse, and neither is worth failing a run over. A model
            // that does not take effort gets requests without it from here on; one that will not
            // accept the thinking blocks sent back gets this request without them.
            if (refused(r, "ttl") && withoutLongCache.compareAndSet(false, true)) {
                log.warn("Model {} refused the one-hour cache ({}) — using the default cache lifetime", mdl, r.getError());
                r = retry(call);
            }
            if (refused(r, "effort", "output_config") && effort != null && withoutEffort.compareAndSet(false, true)) {
                log.warn("Model {} refused effort '{}' ({}) — sending requests without it", mdl, effort, r.getError());
                r = retry(call);
            }
            if (refused(r, "thinking", "signature") && hasThinking(sent)
                    && withoutThinkingReplay.compareAndSet(false, true)) {
                log.warn("Model {} refused the thinking blocks sent back ({}) — sending none from here on", mdl, r.getError());
                List<Map<String, Object>> plain = withoutThinking(messages);
                r = retry(() -> idleTimeoutMs > 0
                        ? callApiStreamed(systemPrompt, plain, t, defaultMaxTokens, apiKey, mdl, ceiling)
                        : callApi(systemPrompt, plain, t, defaultMaxTokens, apiKey, mdl, ceiling));
            }
            return r;
        } catch (Exception e) {
            log.error("send() failed", e);
            return ClaudeResponse.error("Exception: " + describe(e));
        }
    }

    /**
     * How long a non-streaming request may take, given how many tokens it is allowed to write.
     *
     * <p>A flat 120 s was sized for a 2,048-token cap. Output arrives at very roughly 30–80 tokens a
     * second, so a reply allowed 16,000 tokens can legitimately need several minutes, and a flat
     * limit turns every long write into a timeout plus three retries. The ceiling scales with the
     * cap and never drops below the old 120 s; a reply that finishes early returns early whatever
     * this says.
     */
    public static int requestTimeoutSeconds(int maxTokens) {
        return Math.max(120, 60 + maxTokens / 30);
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

    /** A 400 whose message names one of {@code words} — the request was refused for that setting. */
    static boolean refused(ClaudeResponse r, String... words) {
        if (r == null || !r.hasError()) return false;
        String e = r.getError().toLowerCase();
        if (!e.contains("400") && !e.contains("invalid_request")) return false;
        for (String w : words) if (e.contains(w)) return true;
        return false;
    }

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
                String msg = "Exception: " + describe(e);
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
                        .uri(URI.create(apiUrl))
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

    /** Marks the end of a streamed body in {@link #callApiStreamed}'s queue. */
    private static final Object END_OF_BODY = new Object();

    /**
     * One streamed attempt: the same request as {@link #callApi} with {@code stream: true}, the
     * events assembled into the same {@link ClaudeResponse} {@link #parseResponse} would build.
     *
     * <p>Every line of the body — and the failure, if the exchange fails — goes through a queue that
     * is read with the idle window as its timeout, so a connection that stops delivering is noticed
     * within that window whether it stalls before the headers or in the middle of the reply. The
     * attempt then throws an exception whose message {@link #isTransient} accepts, and
     * {@link #retry} makes the next one. {@code ceilingSeconds} still bounds the whole attempt.
     */
    ClaudeResponse callApiStreamed(String systemPrompt, List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools, int maxTokens,
                                   String apiKey, String model, int ceilingSeconds) throws Exception {
        String json = objectMapper.writeValueAsString(body(systemPrompt, messages, tools, maxTokens, apiKey, model, true));
        log.debug("Claude API (streamed) — model:{} messages:{} tools:{}", model, messages.size(), tools != null ? tools.size() : 0);

        java.util.concurrent.BlockingQueue<Object> queue = new java.util.concurrent.LinkedBlockingQueue<>();
        int[] status = {0};
        java.net.http.HttpHeaders[] responseHeaders = {null};
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Flow.Subscription> subscription =
                new java.util.concurrent.atomic.AtomicReference<>();

        java.util.concurrent.Flow.Subscriber<String> lines = new java.util.concurrent.Flow.Subscriber<>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(String line) { queue.offer(line); }
            @Override public void onError(Throwable t) { queue.offer(t); }
            @Override public void onComplete()         { queue.offer(END_OF_BODY); }
        };
        HttpResponse.BodyHandler<Void> handler = info -> {
            status[0] = info.statusCode();
            responseHeaders[0] = info.headers();
            return HttpResponse.BodySubscribers.fromLineSubscriber(lines);
        };

        java.util.concurrent.CompletableFuture<HttpResponse<Void>> exchange = HTTP.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .timeout(Duration.ofSeconds(ceilingSeconds))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .headers(headers(apiKey, true))
                        .build(),
                handler);
        // A failure before any body arrives (DNS, refused connection) never reaches the subscriber.
        exchange.whenComplete((r, ex) -> { if (ex != null) queue.offer(unwrap(ex)); });

        long deadline = System.currentTimeMillis() + ceilingSeconds * 1000L;
        SseAssembler sse = new SseAssembler(objectMapper);
        StringBuilder errorBody = new StringBuilder();
        // Read to the body's own end rather than stopping at message_stop: cancelling a body the
        // server is about to close costs the pooled connection, and the next call its TLS handshake.
        boolean completed = false;
        try {
            while (true) {
                long now = System.currentTimeMillis();
                long wait = Math.min(idleTimeoutMs, deadline - now);
                if (wait <= 0) {
                    throw new java.io.IOException("request timed out after " + ceilingSeconds + "s");
                }
                Object item;
                try {
                    item = queue.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (item == null) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw new java.io.IOException("request timed out after " + ceilingSeconds + "s");
                    }
                    throw new java.io.IOException("stream idle timeout: nothing received for "
                            + (idleTimeoutMs / 1000) + "s — the connection stalled");
                }
                if (item == END_OF_BODY) { completed = true; break; }
                if (item instanceof Exception e) throw e;
                if (item instanceof Throwable t) throw new java.io.IOException(t);
                String line = (String) item;
                if (status[0] != 200) { errorBody.append(line).append('\n'); continue; }
                sse.accept(line);
            }
        } finally {
            if (!completed) {
                // Abandoned — stalled, timed out or failed: drop the exchange so it stops holding a connection.
                java.util.concurrent.Flow.Subscription s = subscription.get();
                if (s != null) s.cancel();
                exchange.cancel(true);
            }
        }

        if (status[0] != 200) {
            String err = extractApiError(errorBody.toString().strip(), status[0]);
            if (status[0] == 429 && responseHeaders[0] != null) {
                long secs = responseHeaders[0].firstValue("retry-after").map(AnthropicClient::parseRetryAfterSeconds).orElse(0L);
                if (secs > 0) err = err + " [retry-after=" + secs + "s]";
            }
            return ClaudeResponse.error(err);
        }
        return sse.result();
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException
                || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Builds a {@link ClaudeResponse} from the Messages API's server-sent events, the same one
     * {@link #parseResponse} builds from the buffered JSON: text and {@code tool_use} blocks in
     * order (a tool's input is its {@code input_json_delta} pieces joined, then parsed), the stop
     * reason, and usage. Other block types — none are requested today — are skipped, as there.
     */
    static final class SseAssembler {
        private final ObjectMapper mapper;
        private final SortedMap<Integer, Map<String, Object>> blocks = new TreeMap<>();
        private final ClaudeResponse response = new ClaudeResponse();
        private String error;
        private boolean done;

        SseAssembler(ObjectMapper mapper) { this.mapper = mapper; }

        /** One line of the event stream; only {@code data:} lines carry anything ({@code event:} repeats the type). */
        void accept(String line) throws Exception {
            if (line == null || !line.startsWith("data:")) return;
            String data = line.substring(5).strip();
            if (data.isEmpty() || "[DONE]".equals(data)) return;
            JsonNode ev = mapper.readTree(data);
            switch (ev.path("type").asText()) {
                case "message_start" -> {
                    JsonNode m = ev.path("message");
                    response.setId(m.path("id").asText());
                    response.setModel(m.path("model").asText());
                    usage(m.path("usage"));
                }
                case "content_block_start" -> {
                    JsonNode b = ev.path("content_block");
                    Map<String, Object> block = new HashMap<>();
                    block.put("type", b.path("type").asText());
                    block.put("text", new StringBuilder(b.path("text").asText("")));
                    block.put("json", new StringBuilder());
                    block.put("id", b.path("id").asText());
                    block.put("name", b.path("name").asText());
                    block.put("input", b.path("input"));
                    // Thinking: its text (empty when display is omitted) and the signature that
                    // lets it be sent back. Redacted thinking is one opaque `data` field.
                    if ("thinking".equals(b.path("type").asText())) {
                        ((StringBuilder) block.get("text")).append(b.path("thinking").asText(""));
                        block.put("signature", new StringBuilder(b.path("signature").asText("")));
                    }
                    if (b.has("data")) block.put("data", b.path("data").asText());
                    blocks.put(ev.path("index").asInt(), block);
                }
                case "content_block_delta" -> {
                    Map<String, Object> block = blocks.get(ev.path("index").asInt());
                    if (block == null) return;
                    JsonNode d = ev.path("delta");
                    switch (d.path("type").asText()) {
                        case "text_delta"       -> ((StringBuilder) block.get("text")).append(d.path("text").asText());
                        case "input_json_delta" -> ((StringBuilder) block.get("json")).append(d.path("partial_json").asText());
                        case "thinking_delta"   -> ((StringBuilder) block.get("text")).append(d.path("thinking").asText());
                        case "signature_delta"  -> {
                            Object sig = block.get("signature");
                            if (sig instanceof StringBuilder sb) sb.append(d.path("signature").asText());
                            else block.put("signature", new StringBuilder(d.path("signature").asText()));
                        }
                        default -> { }
                    }
                }
                case "message_delta" -> {
                    String stop = ev.path("delta").path("stop_reason").asText(null);
                    if (stop != null) response.setStopReason(stop);
                    usage(ev.path("usage"));
                }
                case "message_stop" -> done = true;
                case "error" -> {
                    JsonNode e = ev.path("error");
                    error = "API error: " + statusFor(e.path("type").asText()) + " - "
                            + e.path("message").asText(e.path("type").asText());
                    done = true;
                }
                default -> { }   // ping, content_block_stop
            }
        }

        /**
         * The HTTP status the same failure would have come back as unstreamed, so {@link #retry}
         * classifies a mid-stream {@code overloaded_error} exactly as it does a 529.
         */
        private static String statusFor(String type) {
            return switch (type) {
                case "overloaded_error"     -> "529";
                case "api_error"            -> "500";
                case "rate_limit_error"     -> "429";
                case "invalid_request_error" -> "400";
                case "authentication_error" -> "401";
                case "permission_error"     -> "403";
                case "not_found_error"      -> "404";
                case "request_too_large"    -> "413";
                default -> type;
            };
        }

        /** Later events repeat the running totals; a field they leave out keeps its earlier value. */
        private void usage(JsonNode u) {
            if (u == null || u.isMissingNode()) return;
            if (u.has("input_tokens"))                response.setInputTokens(u.path("input_tokens").asInt());
            if (u.has("output_tokens"))               response.setOutputTokens(u.path("output_tokens").asInt());
            if (u.has("cache_creation_input_tokens")) response.setCacheCreationInputTokens(u.path("cache_creation_input_tokens").asInt());
            if (u.has("cache_read_input_tokens"))     response.setCacheReadInputTokens(u.path("cache_read_input_tokens").asInt());
        }

        @SuppressWarnings("unchecked")
        ClaudeResponse result() throws Exception {
            if (error != null) return ClaudeResponse.error(error);
            if (!done) {
                // The body ended without message_stop: the server closed the stream early. Worded
                // like java.net.http's own early-close error, which isTransient already retries.
                throw new java.io.IOException("EOF reached while reading: the stream ended before message_stop");
            }
            List<ContentBlock> content = new ArrayList<>();
            for (Map<String, Object> b : blocks.values()) {
                String type = (String) b.get("type");
                if ("text".equals(type)) {
                    content.add(ContentBlock.text(b.get("text").toString()));
                } else if ("thinking".equals(type)) {
                    Object sig = b.get("signature");
                    content.add(ContentBlock.thinking(b.get("text").toString(), sig == null ? "" : sig.toString()));
                } else if ("redacted_thinking".equals(type)) {
                    content.add(ContentBlock.redactedThinking((String) b.get("data")));
                } else if ("tool_use".equals(type)) {
                    String json = b.get("json").toString();
                    Map<String, Object> input;
                    try {
                        input = json.isBlank()
                                ? mapper.convertValue(b.get("input"), Map.class)
                                : mapper.readValue(json, Map.class);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException incomplete) {
                        // A tool call whose input JSON stops part-way. Task 920 (2026-10-15) lost a
                        // 20-minute run to this: the parse error ended the run as a model error.
                        // Cut off at the output limit, it is a truncated turn the loop already
                        // answers (MAX_TOKENS_STOP) — kept, with no input. Otherwise the stream
                        // broke in transit: said as the early close it is, which retry() repeats.
                        if (!ReactLoop.MAX_TOKENS_STOP.equals(response.getStopReason())) {
                            throw new java.io.IOException("EOF reached while reading: tool call '" + b.get("name")
                                    + "' arrived with incomplete input (" + json.length() + " chars)");
                        }
                        input = new LinkedHashMap<>();
                    }
                    content.add(ContentBlock.toolUse((String) b.get("id"), (String) b.get("name"),
                            input != null ? input : new LinkedHashMap<>()));
                }
            }
            response.setContent(content);
            return response;
        }
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
                        .uri(URI.create(apiUrl))
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

        Map<String, Object> cache = cacheControl();
        String sysText = oauth ? CLAUDE_CODE_SYS : (systemPrompt != null ? systemPrompt : "");
        b.put("system", List.of(Map.of(
                "type", "text", "text", sysText,
                "cache_control", cache)));

        List<Map<String, Object>> effectiveMessages = (oauth && !blank(systemPrompt))
                ? oauthMessages(messages, systemPrompt) : messages;
        b.put("messages", withCacheBreakpoint(effectiveMessages, cache));

        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> cached = new ArrayList<>(tools);
            Map<String, Object> last = new LinkedHashMap<>(cached.get(cached.size() - 1));
            last.put("cache_control", cache);
            cached.set(cached.size() - 1, last);
            b.put("tools", cached);
        }

        if (effort != null && !withoutEffort.get()) b.put("output_config", Map.of("effort", effort));
        if (stream) b.put("stream", true);
        return b;
    }

    /**
     * THE CACHE LIVES AN HOUR, not five minutes — on the tools, the system prompt and the tail alike.
     *
     * <p>Task 944 (2026-10-15): a run's system-prompt entry is written on its first turn and never read
     * again — every later turn reads the longer entry at the tail — so it expired five minutes in, and
     * each later prune of old messages fell back to the tools alone and rewrote ~130k tokens. The tail
     * entry was the same story between runs: the platform's gates and merges took 2–8 minutes, and the
     * resumed run rewrote 120–182k tokens. A one-hour write costs 2x instead of 1.25x on the few
     * thousand tokens a turn adds; a miss costs 1.25x on the whole conversation.
     *
     * <p>All three markers carry the same TTL, so the rule that a longer-lived entry must come before a
     * shorter one holds. A model or endpoint that refuses the field gets the default from then on.
     */
    static final Map<String, Object> CACHE_ONE_HOUR;
    static {
        // Ordered: Map.of iterates in a per-JVM random order, and the bytes of a request are what a
        // cache is keyed on and what a test compares.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ephemeral");
        m.put("ttl", "1h");
        CACHE_ONE_HOUR = java.util.Collections.unmodifiableMap(m);
    }
    static final Map<String, Object> CACHE_DEFAULT  = Map.of("type", "ephemeral");

    /** Set once the endpoint has refused {@code cache_control.ttl}: this client sends the default from then on. */
    private final java.util.concurrent.atomic.AtomicBoolean withoutLongCache = new java.util.concurrent.atomic.AtomicBoolean();

    Map<String, Object> cacheControl() {
        return withoutLongCache.get() ? CACHE_DEFAULT : CACHE_ONE_HOUR;
    }

    /** Set once a model has refused {@code output_config.effort}: this client stops sending it. */
    private final java.util.concurrent.atomic.AtomicBoolean withoutEffort = new java.util.concurrent.atomic.AtomicBoolean();
    /** Set once a model has refused the thinking blocks sent back: this client strips them from then on. */
    private final java.util.concurrent.atomic.AtomicBoolean withoutThinkingReplay = new java.util.concurrent.atomic.AtomicBoolean();

    /** Whether any assistant turn in {@code messages} carries a thinking block. */
    static boolean hasThinking(List<Map<String, Object>> messages) {
        if (messages == null) return false;
        for (Map<String, Object> m : messages) {
            if (m != null && m.get("content") instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> block && isThinking(block.get("type"))) return true;
                }
            }
        }
        return false;
    }

    /** The conversation without its thinking blocks — a fallback when a request carrying them is refused. */
    static List<Map<String, Object>> withoutThinking(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Map<String, Object> m : messages) {
            if (m != null && m.get("content") instanceof List<?> blocks) {
                List<Object> kept = new ArrayList<>();
                for (Object b : blocks) {
                    if (!(b instanceof Map<?, ?> block && isThinking(block.get("type")))) kept.add(b);
                }
                Map<String, Object> copy = new LinkedHashMap<>(m);
                copy.put("content", kept);
                out.add(copy);
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isThinking(Object type) {
        return "thinking".equals(type) || "redacted_thinking".equals(type);
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
    /** Public so the backend's chat path (AnthropicService) applies the same rule rather than a copy. */
    public static List<Map<String, Object>> withCacheBreakpoint(List<Map<String, Object>> messages) {
        return withCacheBreakpoint(messages, CACHE_DEFAULT);
    }

    /** As above, with the given {@code cache_control} — the tool loop's is {@link #cacheControl()}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> withCacheBreakpoint(List<Map<String, Object>> messages,
                                                         Map<String, Object> cacheControl) {
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
        lastBlock.put("cache_control", cacheControl);
        lastMessage.put("content", blocks);

        List<Map<String, Object>> result = new ArrayList<>(messages);
        result.set(lastIdx, lastMessage);
        return result;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    ClaudeResponse parseResponse(String body) {
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
                } else if ("thinking".equals(type)) {
                    blocks.add(ContentBlock.thinking(block.path("thinking").asText(""), block.path("signature").asText("")));
                } else if ("redacted_thinking".equals(type)) {
                    blocks.add(ContentBlock.redactedThinking(block.path("data").asText()));
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
        return err != null && (TRANSIENT_KEYWORDS.stream().anyMatch(err::contains)
                || SERVER_ERROR.matcher(err).find());
    }

    /**
     * Any 5xx from the API or the edge in front of it. Only 503 and 529 were listed, so a 502, 504 or
     * Cloudflare's 520–524 ("error code: 520") failed a run outright (2026-09-30, a migration run ten
     * minutes in). A server error is the server's, and worth the retry.
     */
    private static final java.util.regex.Pattern SERVER_ERROR =
            java.util.regex.Pattern.compile("API error: 5\\d\\d\\b");

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
        private String type, text, toolId, toolName, signature, data;
        private Map<String, Object> toolInput;

        public static ContentBlock text(String t) {
            ContentBlock b = new ContentBlock(); b.type = "text"; b.text = t; return b;
        }
        /** The model's reasoning — {@code thinking} may be empty (display omitted); the signature is what counts. */
        public static ContentBlock thinking(String thinking, String signature) {
            ContentBlock b = new ContentBlock(); b.type = "thinking"; b.text = thinking; b.signature = signature; return b;
        }
        public static ContentBlock redactedThinking(String data) {
            ContentBlock b = new ContentBlock(); b.type = "redacted_thinking"; b.data = data; return b;
        }
        public String getSignature()                    { return signature; }
        public String getData()                         { return data; }
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
