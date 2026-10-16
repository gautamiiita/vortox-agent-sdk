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
 * Task 944 (2026-10-15): the system-prompt cache entry expired five minutes into each run — it is
 * written on the first turn and never read again — and the tail entry expired while the platform's
 * gates ran between runs, so prunes and resumes rewrote 120–180k tokens each. Every marker now asks
 * for the one-hour lifetime; an endpoint that refuses it gets the default, and is not asked again.
 */
class AnthropicClientCacheTtlTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ONE_HOUR = "\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}";
    private static final String DEFAULT  = "\"cache_control\":{\"type\":\"ephemeral\"}";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("tools, system prompt and conversation tail all carry the one-hour lifetime")
    void everyMarkerLivesAnHour() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(false), 2000);

        assertFalse(c.send("sys", hi(), tools(), "sk-test", "claude-opus-5").hasError());

        String body = requests.get(0);
        assertEquals(3, count(body, ONE_HOUR), body);
        assertEquals(0, count(body, DEFAULT), body);
    }

    @Test
    @DisplayName("an endpoint that refuses the ttl is asked again with the default, and never again with it")
    void refusedTtlFallsBackToTheDefault() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(true), 2000);

        AnthropicClient.ClaudeResponse r = c.send("sys", hi(), tools(), "sk-test", "claude-opus-5");
        assertFalse(r.hasError(), r.getError());
        assertTrue(requests.get(0).contains("\"ttl\""));
        assertFalse(requests.get(1).contains("\"ttl\""));
        assertEquals(3, count(requests.get(1), DEFAULT));

        assertFalse(c.send("sys", hi(), tools(), "sk-test", "claude-opus-5").hasError());
        assertEquals(3, requests.size(), "no refused call on the next turn");
        assertFalse(requests.get(2).contains("\"ttl\""));
    }

    @Test
    @DisplayName("the backend chat path's public breakpoint keeps the default lifetime")
    void publicBreakpointIsUnchanged() {
        List<Map<String, Object>> out = AnthropicClient.withCacheBreakpoint(hi());
        @SuppressWarnings("unchecked")
        Map<String, Object> block = ((List<Map<String, Object>>) out.get(0).get("content")).get(0);
        assertEquals(Map.of("type", "ephemeral"), block.get("cache_control"));
    }

    // ── harness ───────────────────────────────────────────────────────────────

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static List<Map<String, Object>> hi() {
        return new ArrayList<>(List.of(Map.of("role", "user", "content", "hi")));
    }

    private static List<Map<String, Object>> tools() {
        return List.of(
                Map.of("name", "grep", "description", "search", "input_schema", Map.of("type", "object")),
                Map.of("name", "read_file", "description", "read", "input_schema", Map.of("type", "object")));
    }

    /** Answers with a short stream; refuses a cache ttl with a 400 when told to. */
    private String serve(boolean refuseTtl) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/v1/messages", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                if (refuseTtl && body.contains("\"ttl\"")) {
                    byte[] err = ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                            + "\"system.0.cache_control.ttl: Extra inputs are not permitted\"}}").getBytes(StandardCharsets.UTF_8);
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
