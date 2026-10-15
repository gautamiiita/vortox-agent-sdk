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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool loop's model calls are streamed, so a stalled connection is noticed within the idle window
 * instead of after a buffered call's ~10-minute timeout, three times over (tasks 717/718, 2026-10-12:
 * 31 minutes silent, then the run restarted from iteration 1). The streamed reply must come out as
 * the very response the buffered call produced.
 */
class AnthropicClientStreamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A reply with prose and a tool call whose input arrives split mid-token, as the API sends it. */
    private static final List<String> EVENTS = List.of(
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-opus-5\",\"usage\":{\"input_tokens\":12,\"cache_creation_input_tokens\":300,\"cache_read_input_tokens\":4000,\"output_tokens\":1}}}",
            "",
            "event: ping",
            "data: {\"type\":\"ping\"}",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Reading the \"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"file.\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"read_file\",\"input\":{}}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"src/ma\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"in.ts\\\", \\\"limit\\\": 40}\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":57}}",
            "data: {\"type\":\"message_stop\"}");

    /** The same reply, as the buffered (non-streamed) call returns it. */
    private static final String BUFFERED = """
            {"id":"msg_1","model":"claude-opus-5","stop_reason":"tool_use",
             "content":[{"type":"text","text":"Reading the file."},
                        {"type":"tool_use","id":"toolu_9","name":"read_file","input":{"path":"src/main.ts","limit":40}}],
             "usage":{"input_tokens":12,"output_tokens":57,"cache_creation_input_tokens":300,"cache_read_input_tokens":4000}}""";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static AnthropicClient client(String url, long idleMs) {
        return new AnthropicClient(MAPPER, "claude-opus-5", 1024, url, idleMs);
    }

    private static void assertSameResponse(AnthropicClient.ClaudeResponse want, AnthropicClient.ClaudeResponse got) {
        assertEquals(want.getError(), got.getError());
        assertEquals(want.getId(), got.getId());
        assertEquals(want.getModel(), got.getModel());
        assertEquals(want.getStopReason(), got.getStopReason());
        assertEquals(want.getInputTokens(), got.getInputTokens());
        assertEquals(want.getOutputTokens(), got.getOutputTokens());
        assertEquals(want.getCacheCreationInputTokens(), got.getCacheCreationInputTokens());
        assertEquals(want.getCacheReadInputTokens(), got.getCacheReadInputTokens());
        assertEquals(want.getContent().size(), got.getContent().size());
        for (int i = 0; i < want.getContent().size(); i++) {
            var w = want.getContent().get(i);
            var g = got.getContent().get(i);
            assertEquals(w.getType(), g.getType());
            assertEquals(w.getText(), g.getText());
            assertEquals(w.getToolId(), g.getToolId());
            assertEquals(w.getToolName(), g.getToolName());
            assertEquals(w.getToolInput(), g.getToolInput());
        }
    }

    @Test
    @DisplayName("the streamed events assemble into exactly the buffered response")
    void assemblesLikeBuffered() throws Exception {
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        for (String line : EVENTS) sse.accept(line);

        assertSameResponse(client("http://unused", 1000).parseResponse(BUFFERED), sse.result());
    }

    @Test
    @DisplayName("an error event mid-stream reads like the same failure unstreamed, so it is retried alike")
    void errorEventIsClassifiedLikeItsStatus() throws Exception {
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        sse.accept("data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":1}}}");
        sse.accept("data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");

        AnthropicClient.ClaudeResponse r = sse.result();
        assertEquals("API error: 529 - Overloaded", r.getError());
        assertTrue(AnthropicClient.isTransient(r.getError()));
    }

    @Test
    @DisplayName("a body that ends before message_stop is a retried early close, not an answer")
    void earlyCloseIsTransient() throws Exception {
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        for (String line : EVENTS.subList(0, 8)) sse.accept(line);

        IOException e = assertThrows(IOException.class, sse::result);
        assertTrue(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(e)), e.getMessage());
    }

    private static List<String> toolCallCutAt(String stopReason) {
        return List.of(
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"model\":\"claude-opus-5\",\"usage\":{\"input_tokens\":1}}}",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"edit_file\",\"input\":{}}}",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"a.ts\\\", \\\"edits\\\": [{\"}}",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\"},\"usage\":{\"output_tokens\":9}}",
                "data: {\"type\":\"message_stop\"}");
    }

    @Test
    @DisplayName("a tool call whose input breaks off mid-stream is a retried early close, not a failed run")
    void incompleteToolInputIsRetried() throws Exception {
        // task 920 (2026-10-15): JsonEOFException ended a 20-minute run as a model error
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        for (String line : toolCallCutAt("tool_use")) sse.accept(line);

        IOException e = assertThrows(IOException.class, sse::result);
        assertTrue(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(e)), e.getMessage());
    }

    @Test
    @DisplayName("cut off at the output limit, the call is kept without input, for the loop's truncated-turn answer")
    void incompleteToolInputAtTheLimitIsATruncatedTurn() throws Exception {
        AnthropicClient.SseAssembler sse = new AnthropicClient.SseAssembler(MAPPER);
        for (String line : toolCallCutAt("max_tokens")) sse.accept(line);

        AnthropicClient.ClaudeResponse r = sse.result();
        assertEquals("max_tokens", r.getStopReason());
        assertEquals("edit_file", r.getToolUses().get(0).getToolName());
        assertTrue(r.getToolUses().get(0).getToolInput().isEmpty());
    }

    @Test
    @DisplayName("over HTTP: a full stream comes back as the buffered response")
    void streamsOverHttp() throws Exception {
        String url = serve(exchange -> {
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String line : EVENTS) out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        });

        AnthropicClient.ClaudeResponse r = client(url, 2000)
                .callApiStreamed("sys", List.of(Map.of("role", "user", "content", "hi")), null, 1024, "sk-test", "m", 30);

        assertSameResponse(client(url, 2000).parseResponse(BUFFERED), r);
    }

    @Test
    @DisplayName("over HTTP: an error status is returned as the same API error the buffered call gives")
    void errorStatusOverHttp() throws Exception {
        String url = serve(exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(529, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });

        AnthropicClient.ClaudeResponse r = client(url, 2000)
                .callApiStreamed("sys", List.of(Map.of("role", "user", "content", "hi")), null, 1024, "sk-test", "m", 30);

        assertEquals("API error: 529 - Overloaded", r.getError());
    }

    @Test
    @DisplayName("a stream that stalls mid-reply is abandoned within the idle window, as a retryable failure")
    void stallMidReplyIsAbandoned() throws Exception {
        String url = serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write((EVENTS.get(1) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(20_000);   // the connection goes quiet
        });

        long start = System.currentTimeMillis();
        IOException e = assertThrows(IOException.class, () -> client(url, 700)
                .callApiStreamed("sys", List.of(Map.of("role", "user", "content", "hi")), null, 1024, "sk-test", "m", 600));

        assertTrue(System.currentTimeMillis() - start < 5_000, "took " + (System.currentTimeMillis() - start) + " ms");
        assertTrue(e.getMessage().contains("idle"), e.getMessage());
        assertTrue(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(e)), e.getMessage());
    }

    @Test
    @DisplayName("a server that never answers is abandoned within the idle window too")
    void stallBeforeHeadersIsAbandoned() throws Exception {
        String url = serve(exchange -> sleep(20_000));

        long start = System.currentTimeMillis();
        IOException e = assertThrows(IOException.class, () -> client(url, 700)
                .callApiStreamed("sys", List.of(Map.of("role", "user", "content", "hi")), null, 1024, "sk-test", "m", 600));

        assertTrue(System.currentTimeMillis() - start < 5_000, "took " + (System.currentTimeMillis() - start) + " ms");
        assertTrue(AnthropicClient.isTransient("Exception: " + AnthropicClient.describe(e)), e.getMessage());
    }

    private interface Handler { void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException; }

    private String serve(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/v1/messages", exchange -> {
            try { handler.handle(exchange); } finally { exchange.close(); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
