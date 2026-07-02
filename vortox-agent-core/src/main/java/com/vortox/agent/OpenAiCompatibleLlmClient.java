package com.vortox.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;

/**
 * {@link LlmClient} implementation for any OpenAI-compatible API endpoint.
 *
 * <p>Translates the Anthropic-format messages and tool definitions that {@link ReactLoop}
 * produces into OpenAI chat-completions format, calls the remote endpoint, and converts
 * the response back to {@link AnthropicClient.ClaudeResponse}.</p>
 *
 * <p>Supported endpoints: {@code POST /api/chat/completions} (ELCA AI gateway style) or
 * {@code POST /v1/chat/completions} (standard OpenAI style) — detected automatically via
 * the {@code completionsPath} constructor argument.</p>
 */
public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private static final HttpClient HTTP = buildHttpClient();

    private static HttpClient buildHttpClient() {
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return HttpClient.newBuilder()
                    .sslContext(ssl)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create HTTP client", e);
        }
    }

    private final ObjectMapper objectMapper;
    private final int maxTokens;
    private final String completionsUrl;

    /**
     * @param objectMapper  shared Jackson mapper
     * @param maxTokens     max tokens per request
     * @param baseUrl       root URL of the API (e.g. {@code https://ai.svc.elca.ch})
     * @param completionsPath path appended to baseUrl (e.g. {@code /api/chat/completions})
     */
    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper, int maxTokens,
                                      String baseUrl, String completionsPath) {
        this.objectMapper   = objectMapper;
        this.maxTokens      = maxTokens;
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.completionsUrl = root + completionsPath;
    }

    /** Convenience constructor using the ELCA gateway path {@code /api/chat/completions}. */
    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper, int maxTokens, String baseUrl) {
        this(objectMapper, maxTokens, baseUrl, "/api/chat/completions");
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public AnthropicClient.ClaudeResponse send(String systemPrompt,
                                                List<Map<String, Object>> messages,
                                                List<Map<String, Object>> tools,
                                                String apiKey,
                                                String model) {
        if (apiKey == null || apiKey.isBlank()) {
            return AnthropicClient.ClaudeResponse.error("API key not configured for local LLM");
        }

        try {
            List<Map<String, Object>> oaiMessages = buildOaiMessages(systemPrompt, messages);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("messages", oaiMessages);
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", translateToolsToOai(tools));
                body.put("tool_choice", "auto");
            }

            String json = objectMapper.writeValueAsString(body);
            log.debug("OpenAI-compat API — url={} model={} messages={}", completionsUrl, model, oaiMessages.size());

            HttpResponse<String> res = HTTP.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(completionsUrl))
                            .timeout(Duration.ofSeconds(120))
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .headers("Content-Type", "application/json",
                                     "Authorization", "Bearer " + apiKey)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                return AnthropicClient.ClaudeResponse.error(
                        "API error: " + res.statusCode() + " - " + truncate(res.body(), 300));
            }

            return parseOaiResponse(res.body());

        } catch (Exception e) {
            log.error("OpenAI-compat send() failed", e);
            return AnthropicClient.ClaudeResponse.error("Exception: " + e.getMessage());
        }
    }

    // ── Message translation (Anthropic → OpenAI) ──────────────────────────────

    private List<Map<String, Object>> buildOaiMessages(String systemPrompt,
                                                        List<Map<String, Object>> messages) {
        List<Map<String, Object>> oai = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            oai.add(Map.of("role", "system", "content", systemPrompt));
        }
        for (Map<String, Object> msg : messages) {
            oai.addAll(translateMessage(msg));
        }
        return oai;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> translateMessage(Map<String, Object> msg) {
        String role    = (String) msg.get("role");
        Object content = msg.get("content");

        // Plain string content (most user messages)
        if (content instanceof String s) {
            return List.of(Map.of("role", role, "content", s));
        }

        if (!(content instanceof List)) {
            return List.of(Map.of("role", role, "content", String.valueOf(content)));
        }

        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

        // ── User message ─────────────────────────────────────────────────────
        if ("user".equals(role)) {
            boolean hasToolResults = blocks.stream()
                    .anyMatch(b -> "tool_result".equals(b.get("type")));

            if (hasToolResults) {
                // Each tool_result → separate "tool" role message
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> block : blocks) {
                    if ("tool_result".equals(block.get("type"))) {
                        String toolCallId = (String) block.get("tool_use_id");
                        Object c = block.get("content");
                        String text = c instanceof String ? (String) c : String.valueOf(c);
                        result.add(Map.of("role", "tool", "tool_call_id", toolCallId, "content", text));
                    }
                }
                return result;
            }

            // Regular blocks — concatenate text
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> block : blocks) {
                if ("text".equals(block.get("type"))) sb.append(block.get("text"));
            }
            return List.of(Map.of("role", "user", "content", sb.toString()));
        }

        // ── Assistant message ─────────────────────────────────────────────────
        if ("assistant".equals(role)) {
            String text = null;
            List<Map<String, Object>> toolCalls = new ArrayList<>();

            for (Map<String, Object> block : blocks) {
                String type = (String) block.get("type");
                if ("text".equals(type)) {
                    text = (String) block.get("text");
                } else if ("tool_use".equals(type)) {
                    String id   = (String) block.get("id");
                    String name = (String) block.get("name");
                    Object input = block.get("input");
                    String argsJson;
                    try {
                        argsJson = input instanceof String ? (String) input
                                : objectMapper.writeValueAsString(input);
                    } catch (Exception e) {
                        argsJson = "{}";
                    }
                    toolCalls.add(Map.of(
                            "id", id != null ? id : UUID.randomUUID().toString(),
                            "type", "function",
                            "function", Map.of("name", name != null ? name : "", "arguments", argsJson)));
                }
            }

            Map<String, Object> oaiMsg = new LinkedHashMap<>();
            oaiMsg.put("role", "assistant");
            oaiMsg.put("content", text != null ? text : "");
            if (!toolCalls.isEmpty()) oaiMsg.put("tool_calls", toolCalls);
            return List.of(oaiMsg);
        }

        return List.of(Map.of("role", role, "content", ""));
    }

    // ── Tool translation (Anthropic → OpenAI) ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> translateToolsToOai(List<Map<String, Object>> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.getOrDefault("name", ""));
            fn.put("description", tool.getOrDefault("description", ""));
            // Anthropic uses "input_schema"; OpenAI uses "parameters"
            Object schema = tool.get("input_schema");
            fn.put("parameters", schema != null ? schema
                    : Map.of("type", "object", "properties", Map.of()));
            result.add(Map.of("type", "function", "function", fn));
        }
        return result;
    }

    // ── Response parsing (OpenAI → ClaudeResponse) ────────────────────────────

    @SuppressWarnings("unchecked")
    private AnthropicClient.ClaudeResponse parseOaiResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);

            if (root.has("error")) {
                String msg = root.path("error").path("message").asText(root.path("error").asText());
                return AnthropicClient.ClaudeResponse.error(msg);
            }

            JsonNode choice  = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("stop");

            List<AnthropicClient.ContentBlock> blocks = new ArrayList<>();

            // Text content (may be null for pure tool-call responses)
            String textContent = message.path("content").isNull()
                    ? null : message.path("content").asText(null);
            if (textContent != null && !textContent.isBlank()) {
                blocks.add(AnthropicClient.ContentBlock.text(textContent));
            }

            // Tool calls
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    String id   = tc.path("id").asText(UUID.randomUUID().toString());
                    String name = tc.path("function").path("name").asText();
                    String argsStr = tc.path("function").path("arguments").asText("{}");
                    Map<String, Object> input;
                    try {
                        input = objectMapper.readValue(argsStr, Map.class);
                    } catch (Exception e) {
                        input = Map.of("_raw", argsStr);
                    }
                    blocks.add(AnthropicClient.ContentBlock.toolUse(id, name, input));
                }
            }

            // If no content at all, add empty text so ReactLoop's text path works
            if (blocks.isEmpty()) {
                blocks.add(AnthropicClient.ContentBlock.text(""));
            }

            AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
            r.setContent(blocks);
            // Map OpenAI finish_reason back to Anthropic stop_reason
            r.setStopReason("tool_calls".equals(finishReason) ? "tool_use" : "end_turn");

            JsonNode usage = root.path("usage");
            r.setInputTokens(usage.path("prompt_tokens").asInt());
            r.setOutputTokens(usage.path("completion_tokens").asInt());

            return r;

        } catch (Exception e) {
            log.error("OpenAI-compat response parse error: {}", truncate(body, 500), e);
            return AnthropicClient.ClaudeResponse.error("Parse error: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
