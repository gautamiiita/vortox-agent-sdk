package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-16: two ways a run stopped paying for what it does not use.
 *
 * <p>Tools it never calls are deferred and loaded on request, without breaking the cache (ToolLoading).
 * The conversation is no longer pruned in place as it grows — each prune rewrote the cache — but
 * compacted once into the agent's own summary when it nears the context window (Compaction).
 */
class ReactLoopCompactionAndToolLoadingTest {

    /** Plays scripted turns; answers a compaction request with a summary; records every request. */
    static final class Scripted implements LlmClient {
        final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();
        final List<List<Map<String, Object>>> requests = new ArrayList<>();
        final List<List<Map<String, Object>>> summaryRequests = new ArrayList<>();
        String summary = "Done so far: edited a.ts. Next: run the tests.";

        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            if (String.valueOf(messages).contains("This conversation is about to be compacted")) {
                summaryRequests.add(new ArrayList<>(messages));
                return text(summary, 50_000);
            }
            requests.add(new ArrayList<>(messages));
            return turns.poll();
        }
    }

    static AnthropicClient.ClaudeResponse text(String body, int contextTokens) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text(body)));
        r.setStopReason("end_turn");
        r.setCacheReadInputTokens(contextTokens);
        r.setOutputTokens(10);
        return r;
    }

    static AnthropicClient.ClaudeResponse call(String id, String tool, Map<String, Object> input, int contextTokens) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.toolUse(id, tool, input)));
        r.setStopReason("tool_use");
        r.setCacheReadInputTokens(contextTokens);
        r.setOutputTokens(20);
        return r;
    }

    static Map<String, Object> tool(String name, String description, boolean deferred) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", Map.of("type", "object"));
        if (deferred) t.put("defer_loading", true);
        return t;
    }

    static ReactLoop loop(Scripted client, int compactAt) {
        AgentConfig config = AgentConfig.builder().agentId("dev-1").apiKey("k").maxIterations(10)
                .enableExecuteCommand(false).compactAtTokens(compactAt)
                .tool(tool("read_file", "Read a file.", false))
                .tool(tool("browser_navigate", "Open a page in the browser. Then snapshot it.", true))
                .tool(tool("docker_build", "Build an image.", true))
                .toolExecutor((name, params, runId) -> "ran " + name)
                .build();
        return new ReactLoop(config, client, Runnable::run);
    }

    @Nested
    class Loading {
        @Test
        @DisplayName("load_tools names each deferred tool by its first sentence, and only deferred ones")
        void definitionListsDeferredTools() {
            List<Map<String, Object>> tools = List.of(tool("read_file", "Read.", false),
                    tool("browser_navigate", "Open a page in the browser. Then snapshot it.", true));
            String d = String.valueOf(ToolLoading.definition(tools).get("description"));
            assertTrue(d.contains("- browser_navigate: Open a page in the browser."), d);
            assertFalse(d.contains("Then snapshot it"), d);
            assertFalse(d.contains("read_file"), d);
        }

        @Test
        @DisplayName("loading says what loaded, what already was, and what is not deferred")
        void loadAnswers() {
            Set<String> pending = new java.util.LinkedHashSet<>();
            String r = ToolLoading.load(List.of("docker_build", "browser_navigate", "read_file"),
                    Set.of("docker_build", "browser_navigate"), Set.of("browser_navigate"), pending);
            assertEquals(Set.of("docker_build"), pending);
            assertTrue(r.contains("Loaded: docker_build"), r);
            assertTrue(r.contains("Already loaded: browser_navigate"), r);
            assertTrue(r.contains("Not a deferred tool: read_file"), r);
        }

        @Test
        @DisplayName("a loaded tool is surfaced by a system-role tool_addition appended after the turn's results")
        void loadedToolIsSurfacedNextTurn() {
            Scripted c = new Scripted();
            c.turns.add(call("u1", ToolLoading.NAME, Map.of("names", List.of("browser_navigate")), 1_000));
            c.turns.add(call("u2", "browser_navigate", Map.of(), 1_100));
            c.turns.add(text("done", 1_200));

            AgentResult r = loop(c, 0).run("Check the page.");

            assertTrue(r.isSuccess(), String.valueOf(r.error()));
            List<Map<String, Object>> second = c.requests.get(1);
            Map<String, Object> last = second.get(second.size() - 1);
            assertEquals("system", last.get("role"));
            assertEquals(Set.of("browser_navigate"), ToolLoading.surfaced(second));
            // Appended: the first request is an unchanged prefix of the second.
            assertEquals(c.requests.get(0), second.subList(0, c.requests.get(0).size()));
        }

        @Test
        @DisplayName("the cache marker skips a trailing tool change and a deferred last tool")
        void cacheMarkersSkipToolChanges() {
            List<Map<String, Object>> msgs = new ArrayList<>(List.of(
                    Map.of("role", "user", "content", "hi"),
                    ToolLoading.additionMessage(List.of("docker_build"))));
            List<Map<String, Object>> marked = AnthropicClient.withCacheBreakpoint(msgs, AnthropicClient.CACHE_ONE_HOUR);
            assertTrue(String.valueOf(marked.get(0)).contains("cache_control"));
            assertFalse(String.valueOf(marked.get(1)).contains("cache_control"));
        }

        @Test
        @DisplayName("without deferral the tools lose their flag and the conversation its tool changes")
        void fallbackSendsEverythingInFull() {
            List<Map<String, Object>> tools = List.of(tool("a", "A.", false), tool("b", "B.", true));
            assertTrue(AnthropicClient.hasDeferred(tools));
            assertFalse(AnthropicClient.hasDeferred(AnthropicClient.withoutDeferral(tools)));
            List<Map<String, Object>> msgs = List.of(Map.of("role", "user", "content", "hi"),
                    ToolLoading.additionMessage(List.of("b")));
            assertEquals(1, AnthropicClient.withoutToolChanges(msgs).size());
        }
    }

    @Nested
    class Compacting {
        @Test
        @DisplayName("below the threshold nothing in the conversation is touched — every request extends the last")
        void belowThresholdOnlyAppends() {
            Scripted c = new Scripted();
            for (int i = 0; i < 4; i++) c.turns.add(call("u" + i, "read_file", Map.of("path", "a" + i), 10_000 * (i + 1)));
            c.turns.add(text("done", 60_000));

            loop(c, 800_000).run("Fix it.");

            assertTrue(c.summaryRequests.isEmpty());
            for (int i = 1; i < c.requests.size(); i++) {
                List<Map<String, Object>> prev = c.requests.get(i - 1);
                assertEquals(prev, c.requests.get(i).subList(0, prev.size()), "request " + i + " rewrote history");
            }
        }

        @Test
        @DisplayName("at the threshold the run continues from its task plus its own summary")
        void atThresholdTheRunContinuesFromASummary() {
            Scripted c = new Scripted();
            c.turns.add(call("u1", "read_file", Map.of("path", "a.ts"), 120_000));
            c.turns.add(text("done", 5_000));

            AgentResult r = loop(c, 100_000).run("Fix the handler.");

            assertTrue(r.isSuccess(), String.valueOf(r.error()));
            assertEquals(1, c.summaryRequests.size());
            List<Map<String, Object>> after = c.requests.get(1);
            assertEquals(1, after.size(), "one message: the task and the summary");
            String only = String.valueOf(after.get(0));
            assertTrue(only.contains("Fix the handler."), only);
            assertTrue(only.contains("Done so far: edited a.ts."), only);
            assertFalse(only.contains("tool_result"), only);
        }

        @Test
        @DisplayName("tools loaded before a compaction are loaded after it")
        void loadedToolsSurviveCompaction() {
            List<Map<String, Object>> c = Compaction.compacted(Map.of("role", "user", "content", "Task."),
                    "Summary.", List.of("browser_navigate"));
            assertEquals(2, c.size());
            assertEquals(Set.of("browser_navigate"), ToolLoading.surfaced(c));
        }

        @Test
        @DisplayName("the summary request is a copy: the run's own conversation is not changed by asking")
        void summaryRequestIsACopy() {
            List<Map<String, Object>> msgs = new ArrayList<>(List.of(Map.of("role", "user", "content", "Task.")));
            List<Map<String, Object>> asked = Compaction.summaryRequest(msgs);
            assertEquals("Task.", msgs.get(0).get("content"));
            assertTrue(String.valueOf(asked).contains("about to be compacted"));
        }

        @Test
        @DisplayName("0 turns compaction off")
        void zeroIsOff() {
            assertFalse(Compaction.due(5_000_000, 0));
            assertTrue(Compaction.due(800_000, 800_000));
        }
    }
}
