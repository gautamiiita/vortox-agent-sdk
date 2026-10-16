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

/** The agent-loop findings of the 2026-10-16 performance review. */
class PerfReview20261016Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("an API error is retried by its status, not by digits that happen to be in its text")
    void transientByStatus() {
        assertFalse(AnthropicClient.isTransient("API error: 400 - prompt is too long: 205291 tokens > 200000 maximum"));
        assertFalse(AnthropicClient.isTransient("API error: 401 - invalid x-api-key (timeout?)"));
        assertTrue(AnthropicClient.isTransient("API error: 529 - Overloaded"));
        assertTrue(AnthropicClient.isTransient("API error: 429 - rate_limit_error"));
        assertTrue(AnthropicClient.isTransient("API error: 502 - Bad Gateway"));
        // Not an API error: the network's own words still count.
        assertTrue(AnthropicClient.isTransient("Exception: IOException: EOF reached while reading"));
        assertTrue(AnthropicClient.isTransient("Exception: SSLException: bad_record_mac"));
    }

    @Test
    @DisplayName("a one-shot call carries no cache markers; a loop call carries them")
    void oneShotIsUncached() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(), 2000);
        List<Map<String, Object>> tools = List.of(Map.of("name", "grep", "description", "d", "input_schema", Map.of("type", "object")));

        assertFalse(c.sendWithoutCache("sys", hi(), tools, "sk-test", "claude-opus-5").hasError());
        assertFalse(requests.get(0).contains("cache_control"), requests.get(0));

        assertFalse(c.send("sys", hi(), tools, "sk-test", "claude-opus-5").hasError());
        assertTrue(requests.get(1).contains("cache_control"));
    }

    @Test
    @DisplayName("a request serialises with sorted keys, whatever order its maps were built in")
    void sortedKeys() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(), 2000);
        c.send("sys", hi(), null, "sk-test", "claude-opus-5");
        String body = requests.get(0);
        assertTrue(body.indexOf("\"max_tokens\"") < body.indexOf("\"messages\""), body);
        assertTrue(body.indexOf("\"messages\"") < body.indexOf("\"model\""), body);
        assertTrue(body.contains("\"cache_control\":{\"ttl\":\"1h\",\"type\":\"ephemeral\"}"), body);
    }

    @Test
    @DisplayName("the previous user turn's end is a cache point too, so a wide turn cannot outrun the lookback")
    void previousTurnIsMarked() {
        List<Map<String, Object>> msgs = List.of(
                Map.of("role", "user", "content", "task"),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use", "id", "a", "name", "grep", "input", Map.of()))),
                Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", "a", "content", "x"))),
                Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use", "id", "b", "name", "grep", "input", Map.of()))),
                Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", "b", "content", "y"))));
        List<Map<String, Object>> out = AnthropicClient.withCacheBreakpoints(msgs, AnthropicClient.CACHE_ONE_HOUR);
        long marked = out.stream().filter(m -> String.valueOf(m).contains("cache_control")).count();
        assertEquals(2, marked);
        assertTrue(String.valueOf(out.get(2)).contains("cache_control"));
        assertTrue(String.valueOf(out.get(4)).contains("cache_control"));
        assertFalse(String.valueOf(msgs.get(2)).contains("cache_control"), "the caller's list is untouched");
    }

    @Test
    @DisplayName("the record of a tool call keeps a JSON result whole and cuts plain text")
    void toolCallRecordIsCapped() {
        String text = "x".repeat(10_000);
        assertTrue(ReactLoop.capped(text, ReactLoop.TOOL_CALL_OUTPUT_KEPT_CHARS).length() < 2_100);
        String json = "{\"path\":\"report.pdf\",\"rows\":\"" + "y".repeat(10_000) + "\"}";
        assertEquals(json, ReactLoop.capped(json, ReactLoop.TOOL_CALL_OUTPUT_KEPT_CHARS));
        assertNull(ReactLoop.capped(null, 10));
    }

    private static List<Map<String, Object>> hi() {
        return new ArrayList<>(List.of(Map.of("role", "user", "content", "hi")));
    }

    private String serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/v1/messages", exchange -> {
            try {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
