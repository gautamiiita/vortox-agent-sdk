package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A person's decision on a held call is acted on, not merely reported.
 *
 * <p>The older {@code resumeAfterApproval} hands the decision text back as the held call's result,
 * so the call itself never runs: an approved call has to be issued again, and a host gate that held
 * it once holds it again. {@code resumeDecided} runs an approved call — exactly once, without asking
 * the gate a second time — and answers it with its real output; a rejected one is never run.
 */
class ReactLoopDecidedApprovalTest {

    static final class Scripted implements LlmClient {
        final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();
        final List<List<Map<String, Object>>> requests = new ArrayList<>();

        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            requests.add(List.copyOf(messages));
            return turns.poll();
        }
    }

    private static AnthropicClient.ClaudeResponse declareTurn() {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text("Recording your declaration."),
                AnthropicClient.ContentBlock.toolUse("d1", "declare", Map.of("purpose", "SUPPORT"))));
        r.setStopReason("tool_use");
        return r;
    }

    private static AnthropicClient.ClaudeResponse answer(String text) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text(text)));
        r.setStopReason("end_turn");
        return r;
    }

    private final AtomicInteger executed = new AtomicInteger();
    private final AtomicInteger gateAsked = new AtomicInteger();
    private final Scripted client = new Scripted();

    private ReactLoop loop() {
        AgentConfig config = AgentConfig.builder().agentId("a").apiKey("k").maxIterations(10)
                .tools(List.of(Map.of("name", "declare", "description", "d",
                        "input_schema", Map.of("type", "object"))))
                .toolExecutor((name, params, runId) -> {
                    executed.incrementAndGet();
                    return "{\"recorded\":true,\"purpose\":\"" + params.get("purpose") + "\"}";
                })
                .toolGate((name, params) -> {
                    if (!"declare".equals(name)) return null;
                    gateAsked.incrementAndGet();
                    return "Declare on your behalf: purpose " + params.get("purpose");
                })
                .build();
        return new ReactLoop(config, client, Runnable::run);
    }

    private AgentResult paused(ReactLoop loop) {
        client.turns.add(declareTurn());
        AgentResult r = loop.run("Read the contacts.", null, "run-1", null, null, "t", null);
        assertTrue(r.needsApproval(), String.valueOf(r.status()));
        assertEquals("Declare on your behalf: purpose SUPPORT", r.approvalRequest());
        assertEquals(0, executed.get(), "nothing runs before the decision");
        return r;
    }

    private static String lastUserContent(List<Map<String, Object>> request) {
        return String.valueOf(request.get(request.size() - 1).get("content"));
    }

    @Test
    @DisplayName("approved: the held call runs once, its real output is the result, the gate is not asked again")
    void approvedRunsTheCall() {
        ReactLoop loop = loop();
        AgentResult p = paused(loop);
        client.turns.add(answer("Recorded. Reading the rows now."));

        AgentResult r = loop.resumeDecided("run-1", p.conversationHistory(), p.approvalAssistantContent(),
                p.approvalToolUseId(), true, null, "t");

        assertTrue(r.isSuccess(), String.valueOf(r.status()));
        assertEquals(1, executed.get());
        assertEquals(1, gateAsked.get(), "the approval covers this call; asking again would loop");
        String result = lastUserContent(client.requests.get(1));
        assertTrue(result.contains("\"recorded\":true") && result.contains("d1"), result);
        assertEquals(1, r.toolCalls().size(), "the approved call is part of the run's record");
        assertEquals("declare", r.toolCalls().get(0).toolName());
    }

    @Test
    @DisplayName("rejected: the held call never runs and the model is told, with the person's note")
    void rejectedDoesNotRun() {
        ReactLoop loop = loop();
        AgentResult p = paused(loop);
        client.turns.add(answer("Understood, I will not read them."));

        AgentResult r = loop.resumeDecided("run-1", p.conversationHistory(), p.approvalAssistantContent(),
                p.approvalToolUseId(), false, "not for this purpose", "t");

        assertTrue(r.isSuccess());
        assertEquals(0, executed.get());
        String result = lastUserContent(client.requests.get(1));
        assertTrue(result.contains("rejected") && result.contains("not for this purpose"), result);
        assertTrue(result.contains("is_error=true"), "a refusal is an error result: " + result);
    }

    @Test
    @DisplayName("a held call that fails when run is reported as failed, not as approved text")
    void approvedButFailing() {
        AgentConfig config = AgentConfig.builder().agentId("a").apiKey("k").maxIterations(10)
                .tools(List.of(Map.of("name", "declare", "description", "d",
                        "input_schema", Map.of("type", "object"))))
                .toolExecutor((name, params, runId) -> {
                    throw new com.vortox.agent.spi.ToolExecutor.ToolExecutionException("HTTP 503");
                })
                .toolGate((name, params) -> "declare".equals(name) ? "Declare?" : null)
                .build();
        ReactLoop loop = new ReactLoop(config, client, Runnable::run);
        client.turns.add(declareTurn());
        AgentResult p = loop.run("Read the contacts.", null, "run-1", null, null, "t", null);
        client.turns.add(answer("The declaration could not be recorded."));

        AgentResult r = loop.resumeDecided("run-1", p.conversationHistory(), p.approvalAssistantContent(),
                p.approvalToolUseId(), true, null, "t");

        assertTrue(r.isSuccess());
        String result = lastUserContent(client.requests.get(1));
        assertTrue(result.contains("HTTP 503") && result.contains("is_error=true"), result);
        assertFalse(r.toolCalls().get(0).success());
    }
}
