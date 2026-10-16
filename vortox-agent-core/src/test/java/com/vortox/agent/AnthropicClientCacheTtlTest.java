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
    // Requests serialise with sorted keys (2026-10-16): ttl before type.
    private static final String ONE_HOUR = "\"cache_control\":{\"ttl\":\"1h\",\"type\":\"ephemeral\"}";
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

    // ── deferred tools ────────────────────────────────────────────────────────

    private static List<Map<String, Object>> toolsWithDeferred() {
        Map<String, Object> deferred = new java.util.LinkedHashMap<>(Map.of("name", "browser_navigate",
                "description", "browse", "input_schema", Map.of("type", "object")));
        deferred.put("defer_loading", true);
        return List.of(Map.of("name", "grep", "description", "search", "input_schema", Map.of("type", "object")), deferred);
    }

    @Test
    @DisplayName("deferred tools go out with the tool-changes beta, and the cache marker on the last loaded tool")
    void deferredToolsCarryTheBeta() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(false), 2000);
        assertFalse(c.send("sys", hi(), toolsWithDeferred(), "sk-test", "claude-opus-5").hasError());
        assertTrue(betas.get(0).contains(AnthropicClient.BETA_TOOL_CHANGES), betas.get(0));
        assertTrue(requests.get(0).contains("\"defer_loading\":true"));
        String toolsJson = requests.get(0).substring(requests.get(0).indexOf("\"tools\""));
        assertEquals(1, count(toolsJson, ONE_HOUR), toolsJson);
        assertTrue(toolsJson.indexOf(ONE_HOUR) < toolsJson.indexOf("browser_navigate"), "the marker is on grep: " + toolsJson);

        assertFalse(c.send("sys", hi(), tools(), "sk-test", "claude-opus-5").hasError());
        assertFalse(betas.get(1).contains(AnthropicClient.BETA_TOOL_CHANGES), "no deferred tools, no beta");
    }

    @Test
    @DisplayName("an endpoint that refuses deferral gets every tool in full and no tool changes, from then on")
    void refusedDeferralSendsEverythingInFull() throws Exception {
        AnthropicClient c = new AnthropicClient(MAPPER, "claude-opus-5", 1024, serve(false, true), 2000);
        List<Map<String, Object>> msgs = new ArrayList<>(hi());
        msgs.add(ToolLoading.additionMessage(List.of("browser_navigate")));

        assertFalse(c.send("sys", msgs, toolsWithDeferred(), "sk-test", "claude-opus-5").hasError());
        assertTrue(requests.get(0).contains("defer_loading"));
        assertFalse(requests.get(1).contains("defer_loading"));
        assertFalse(requests.get(1).contains("tool_addition"));
        assertFalse(betas.get(1).contains(AnthropicClient.BETA_TOOL_CHANGES));

        assertFalse(c.send("sys", msgs, toolsWithDeferred(), "sk-test", "claude-opus-5").hasError());
        assertEquals(3, requests.size(), "no refused call on the next turn");
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
    private String serve(boolean refuseTtl) throws IOException { return serve(refuseTtl, false); }

    private final List<String> betas = new CopyOnWriteArrayList<>();

    private String serve(boolean refuseTtl, boolean refuseDeferral) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/v1/messages", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(body);
                betas.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
                if (refuseDeferral && body.contains("defer_loading")) {
                    byte[] err = ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                            + "\"tools.1.defer_loading: Extra inputs are not permitted\"}}").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(400, err.length);
                    try (OutputStream out = exchange.getResponseBody()) { out.write(err); }
                    return;
                }
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
