package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Claude Opus 5 thinks by default. Its thinking is kept and sent back with the turn it belongs to —
 * dropping it left the model re-deriving its reasoning every turn (task 832, 2026-10-14: 152k output
 * tokens over 98 turns) — and a client can ask for an effort level. Neither may fail a run: a model
 * that refuses either gets requests without it.
 */
class AnthropicClientThinkingEffortTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> THINKING_THEN_TOOL = List.of(
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"model\":\"claude-opus-5\",\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Look at \"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"the handler.\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sigA\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sigB\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"opaque\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"grep\",\"input\":{}}}",
            "data: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pattern\\\":\\\"x\\\"}\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":2}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":900}}",
            "data: {\"type\":\"message_stop\"}");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("a streamed thinking block arrives whole: text, signature, and in order before the tool call")
    void streamedThinkingIsKept() throws Exception {
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        for (String line : THINKING_THEN_TOOL) sse.accept(line);
        AnthropicClient.ClaudeResponse r = sse.result();

        assertEquals(3, r.getContent().size());
        assertEquals("thinking", r.getContent().get(0).getType());
        assertEquals("Look at the handler.", r.getContent().get(0).getText());
        assertEquals("sigAsigB", r.getContent().get(0).getSignature());
        assertEquals("redacted_thinking", r.getContent().get(1).getType());
        assertEquals("opaque", r.getContent().get(1).getData());
        assertEquals("tool_use", r.getContent().get(2).getType());
        assertEquals("", r.getTextContent() == null ? "" : r.getTextContent(), "thinking is not the reply text");
    }

    @Test
    @DisplayName("a buffered thinking block is kept the same way")
    void bufferedThinkingIsKept() {
        AnthropicClient.ClaudeResponse r = new AnthropicClient(MAPPER, "claude-opus-5", 1024).parseResponse("""
                {"id":"m","model":"claude-opus-5","stop_reason":"end_turn",
                 "content":[{"type":"thinking","thinking":"","signature":"s1"},{"type":"text","text":"Done."}],
                 "usage":{"input_tokens":1,"output_tokens":2}}""");
        assertEquals("thinking", r.getContent().get(0).getType());
        assertEquals("s1", r.getContent().get(0).getSignature());
        assertEquals("Done.", r.getTextContent());
    }

    @Test
    @DisplayName("the effort asked for is sent as output_config.effort; none is sent by default")
    void effortIsSent() throws Exception {
        String url = serve(false, false);

        assertFalse(client(url).send("sys", hi(), null, "sk-test", "claude-opus-5").hasError());
        assertFalse(requests.get(0).contains("output_config"), requests.get(0));

        assertFalse(client(url).withEffort("LOW").send("sys", hi(), null, "sk-test", "claude-opus-5").hasError());
        assertTrue(requests.get(1).contains("\"output_config\":{\"effort\":\"low\"}"), requests.get(1));
    }

    @Test
    @DisplayName("a model that refuses effort is asked again without it, and never again with it")
    void refusedEffortIsDropped() throws Exception {
        String url = serve(true, false);
        AnthropicClient c = client(url).withEffort("low");

        AnthropicClient.ClaudeResponse r = c.send("sys", hi(), null, "sk-test", "claude-opus-5");
        assertFalse(r.hasError(), r.getError());
        assertTrue(requests.get(0).contains("output_config"));
        assertFalse(requests.get(1).contains("output_config"));

        assertFalse(c.send("sys", hi(), null, "sk-test", "claude-opus-5").hasError());
        assertEquals(3, requests.size(), "no refused call on the next turn");
        assertFalse(requests.get(2).contains("output_config"));
    }

    @Test
    @DisplayName("a model that refuses the thinking sent back is asked again without it, and from then on")
    void refusedThinkingIsDropped() throws Exception {
        String url = serve(false, true);
        AnthropicClient c = client(url);
        List<Map<String, Object>> withThinking = List.of(
                Map.of("role", "user", "content", "task"),
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "thinking", "thinking", "", "signature", "s"),
                        Map.of("type", "tool_use", "id", "t1", "name", "grep", "input", Map.of()))),
                Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", "t1", "content", "x"))));

        assertFalse(c.send("sys", withThinking, null, "sk-test", "claude-opus-5").hasError());
        assertTrue(requests.get(0).contains("\"type\":\"thinking\""));
        assertFalse(requests.get(1).contains("\"type\":\"thinking\""));
        assertTrue(requests.get(1).contains("tool_use"), "only the thinking is removed");

        assertFalse(c.send("sys", withThinking, null, "sk-test", "claude-opus-5").hasError());
        assertEquals(3, requests.size());
        assertFalse(requests.get(2).contains("\"type\":\"thinking\""));
    }

    @Test
    @DisplayName("other refusals are not retried")
    void otherRefusalsAreReturned() {
        AnthropicClient.ClaudeResponse r = AnthropicClient.ClaudeResponse.error("API error: 400 - prompt is too long");
        assertFalse(AnthropicClient.refused(r, "thinking", "effort"));
        assertTrue(AnthropicClient.refused(
                AnthropicClient.ClaudeResponse.error("API error: 400 - Invalid `signature` in `thinking` block"), "thinking"));
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private static AnthropicClient client(String url) {
        return new AnthropicClient(MAPPER, "claude-opus-5", 1024, url, 2000);
    }

    private static List<Map<String, Object>> hi() {
        return new ArrayList<>(List.of(Map.of("role", "user", "content", "hi")));
    }

    /** Answers with a short stream; refuses effort and/or thinking blocks with a 400 when told to. */
    private String serve(boolean refuseEffort, boolean refuseThinking) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/v1/messages", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                String refusal = refuseEffort && body.contains("output_config")
                        ? "output_config.effort: Extra inputs are not permitted"
                        : refuseThinking && body.contains("\"type\":\"thinking\"")
                        ? "messages.1.content.0: Invalid `signature` in `thinking` block" : null;
                if (refusal != null) {
                    byte[] err = ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\""
                            + refusal.replace("`", "") + "\"}}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, err.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(err); }
                    return;
                }
                exchange.getResponseHeaders().add("content-type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String line : List.of(
                            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"claude-opus-5\",\"usage\":{\"input_tokens\":1}}}",
                            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"ok\"}}",
                            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}",
                            "data: {\"type\":\"message_stop\"}")) {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }
}
