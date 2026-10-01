package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fresh-context completion check. Benchmark m1 (2026-09-30): the developer narrowed "replace
 * every query" to the files it had changed, listed the other two as "unchanged (per requirements)"
 * and reported the requirement MET; its own end-of-run check, made inside that reasoning, agreed.
 */
class ReactLoopCompletionCheckTest {

    private static final String TASK = "Migrate the data layer to Kysely.\n1. Replace every pool.query call.";
    private static final String NOT_MET = """
            {"requirements":[{"requirement":"1. Replace every pool.query call","status":"NOT_MET",
             "evidence":"src/services/cooking.ts still has 14 pool.query calls"}]}""";
    private static final String MET = """
            Here you go:
            ```json
            {"requirements":[{"requirement":"1. Replace every pool.query call","status":"MET","evidence":"none left"}]}
            ```""";

    /** Plays the agent's turns and the check's answers from two scripts, and records what each was sent. */
    static final class ScriptedClient implements LlmClient {
        final Deque<AnthropicClient.ClaudeResponse> agentTurns = new ArrayDeque<>();
        final Deque<AnthropicClient.ClaudeResponse> checkAnswers = new ArrayDeque<>();
        final List<List<Map<String, Object>>> checkRequests = new ArrayList<>();
        final List<List<Map<String, Object>>> checkTools = new ArrayList<>();
        final List<List<Map<String, Object>>> agentRequests = new ArrayList<>();

        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            if (FreshCheck.SYSTEM_PROMPT.equals(systemPrompt)) {
                checkRequests.add(List.copyOf(messages));
                checkTools.add(tools);
                return checkAnswers.isEmpty() ? text(MET) : checkAnswers.poll();
            }
            agentRequests.add(new ArrayList<>(messages));
            return agentTurns.poll();
        }
    }

    static AnthropicClient.ClaudeResponse text(String body) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text(body)));
        r.setStopReason("end_turn");
        r.setInputTokens(100);
        r.setOutputTokens(10);
        return r;
    }

    static AnthropicClient.ClaudeResponse complete(String id, String summary) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text("All requirements MET."),
                AnthropicClient.ContentBlock.toolUse(id, ReactLoop.TASK_COMPLETE_TOOL,
                        Map.of("summary", summary, "outcome", "SUCCESS"))));
        r.setStopReason("tool_use");
        r.setInputTokens(1000);
        r.setOutputTokens(50);
        return r;
    }

    private static ReactLoop loop(ScriptedClient client) {
        AgentConfig config = AgentConfig.builder().agentId("dev-agent-003").apiKey("k").maxIterations(10).build();
        return new ReactLoop(config, client, Runnable::run);
    }

    private static CompletionCheck check(int sendBacks) {
        return new CompletionCheck(TASK, () -> "diff --git a/src/routes/units.ts ...", sendBacks);
    }

    @Test
    @DisplayName("a finding sends the run back with the findings; the fixed work is then accepted")
    void findingSendsBackThenAccepts() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(complete("c1", "Migrated the routes; cooking and waste unchanged per requirements."));
        client.agentTurns.add(complete("c2", "Migrated cooking and waste too."));
        client.checkAnswers.add(text(NOT_MET));
        client.checkAnswers.add(text(MET));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1", check(2));

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertEquals(2, client.checkRequests.size());
        // The second agent turn was sent the findings as the answer to its task_complete.
        List<Map<String, Object>> second = client.agentRequests.get(1);
        String returned = String.valueOf(second.get(second.size() - 1).get("content"));
        assertTrue(returned.contains("cooking.ts still has 14 pool.query calls"), returned);
        assertTrue(returned.contains("c1"), "answers the task_complete call: " + returned);
        assertTrue(r.completionVerdict().ran());
        assertTrue(r.completionVerdict().unmet().isEmpty());
        assertEquals(1, r.completionVerdict().sendBacks());
        assertFalse(r.response().contains("Independent completion check"));
    }

    @Test
    @DisplayName("the check sees the task and the evidence only: no conversation, no tools, no agent report")
    void checkHasAFreshContext() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(complete("c1", "cooking and waste unchanged per requirements"));

        loop(client).run(TASK, null, "run-1", null, null, "m1", check(2));

        assertEquals(1, client.checkRequests.size());
        List<Map<String, Object>> sent = client.checkRequests.get(0);
        assertEquals(1, sent.size(), "one message, not the run's history");
        String body = String.valueOf(sent.get(0).get("content"));
        assertTrue(body.contains("Replace every pool.query call"));
        assertTrue(body.contains("diff --git a/src/routes/units.ts"));
        assertFalse(body.contains("unchanged per requirements"), "the agent's account is not shown");
        assertTrue(client.checkTools.get(0).isEmpty());
    }

    @Test
    @DisplayName("after the last send-back the run ends, with the open findings on the result and in the report")
    void openFindingsTravelOnTheResult() {
        ScriptedClient client = new ScriptedClient();
        for (int i = 0; i < 3; i++) client.agentTurns.add(complete("c" + i, "done"));
        for (int i = 0; i < 3; i++) client.checkAnswers.add(text(NOT_MET));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1", check(2));

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertEquals(3, client.checkRequests.size());
        assertEquals(1, r.completionVerdict().unmet().size());
        assertEquals(2, r.completionVerdict().sendBacks());
        assertTrue(r.response().contains("### Independent completion check"), r.response());
        assertTrue(r.response().contains("cooking.ts still has 14"), r.response());
    }

    @Test
    @DisplayName("a check whose model call fails accepts the completion and says it did not run")
    void failsOpen() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(complete("c1", "done"));
        client.checkAnswers.add(AnthropicClient.ClaudeResponse.error("API error: 529 - overloaded"));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1", check(2));

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertFalse(r.completionVerdict().ran());
        assertTrue(r.completionVerdict().note().contains("529"));
    }

    @Test
    @DisplayName("a plain-text finish is checked too, and the check's tokens count toward the run")
    void plainTextFinishIsChecked() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(text("I am done."));
        client.agentTurns.add(text("Now really done."));
        client.checkAnswers.add(text(NOT_MET));
        client.checkAnswers.add(text(MET));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1", check(2));

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertEquals("Now really done.", r.response());
        assertEquals(400, r.inputTokens(), "two agent turns and two checks");
    }

    static AnthropicClient.ClaudeResponse handoff(String id) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.toolUse(id, ReactLoop.HANDOFF_TOOL,
                Map.of("targetRole", "QA", "reason", "Built; over to QA"))));
        r.setStopReason("tool_use");
        return r;
    }

    @Test
    @DisplayName("g3 2026-10-01: a hand-off is checked like a completion, and sent back the same way")
    void handoffIsChecked() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(handoff("h1"));
        client.agentTurns.add(handoff("h2"));
        client.checkAnswers.add(text(NOT_MET));
        client.checkAnswers.add(text(MET));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "g3", check(2));

        assertEquals(AgentResult.Status.HANDOFF, r.status());
        assertEquals("QA", r.handoffTargetRole());
        assertEquals(2, client.checkRequests.size());
        List<Map<String, Object>> second = client.agentRequests.get(1);
        String returned = String.valueOf(second.get(second.size() - 1).get("content"));
        assertTrue(returned.contains("h1") && returned.contains("hand off again"), returned);
        assertEquals(1, r.completionVerdict().sendBacks());
        assertTrue(r.completionVerdict().unmet().isEmpty());
    }

    @Test
    @DisplayName("without a check nothing changes: one call, no verdict")
    void noCheckNoCall() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(complete("c1", "done"));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1", null);

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertTrue(client.checkRequests.isEmpty());
        assertNull(r.completionVerdict());
    }

    @Test
    @DisplayName("no evidence: the check does not run and the completion stands")
    void noEvidence() {
        ScriptedClient client = new ScriptedClient();
        client.agentTurns.add(complete("c1", "done"));

        AgentResult r = loop(client).run(TASK, null, "run-1", null, null, "m1",
                new CompletionCheck(TASK, () -> "  ", 2));

        assertEquals(AgentResult.Status.SUCCESS, r.status());
        assertTrue(client.checkRequests.isEmpty());
        assertFalse(r.completionVerdict().ran());
    }

    @Test
    @DisplayName("the verdict parser: statuses, fences, and answers that are not a verdict")
    void parsing() {
        CompletionVerdict v = CompletionVerdict.parse("""
                {"requirements":[
                  {"requirement":"1. a","status":"met"},
                  {"requirement":"2. b","status":"Not Met","evidence":"x"},
                  {"requirement":"3. c","status":"partially met"},
                  {"requirement":"4. d","status":"unclear"},
                  {"requirement":"5. e","status":"???"}]}""");
        assertTrue(v.ran());
        assertEquals(List.of("2. b", "3. c"), v.unmet().stream().map(CompletionVerdict.Finding::requirement).toList());
        assertFalse(CompletionVerdict.parse("I think it is fine").ran());
        assertFalse(CompletionVerdict.parse("{\"requirements\":[]}").ran());
        assertFalse(CompletionVerdict.parse(null).ran());
    }
}
