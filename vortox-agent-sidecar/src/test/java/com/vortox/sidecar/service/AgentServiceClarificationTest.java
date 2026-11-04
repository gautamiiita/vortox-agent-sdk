package com.vortox.sidecar.service;

import com.vortox.agent.AgentResult;
import com.vortox.agent.AnthropicClient;
import com.vortox.agent.LlmClient;
import com.vortox.agent.spi.ActivityListener;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A run that stops to ask the person a question hands that question back as its reply. It used to be
 * dropped — the question lives in {@code clarificationQuestion()}, and only response/error were read —
 * so the chat widget showed "I couldn't generate a response. Please try again." instead of asking.
 */
class AgentServiceClarificationTest {

    private static final String QUESTION = "Which region should the sales report cover?";

    private final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();
    private AgentService service;

    private final LlmClient llm = new LlmClient() {
        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            return turns.poll();
        }
    };

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        SkillRegistry registry = new SkillRegistry();
        ReflectionTestUtils.setField(registry, "skillsPath", tmp.resolve("skills").toString());
        ReflectionTestUtils.setField(registry, "allowLocalSkills", true);
        service = new AgentService(registry, mock(ScriptToolExecutor.class)) {
            @Override
            LlmClient resolveLlmClient(String provider, String baseUrl) { return llm; }
        };
        ReflectionTestUtils.setField(service, "defaultModel", "m");
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 1000);
    }

    private static AnthropicClient.ClaudeResponse askTurn() {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.toolUse("c1", "request_clarification",
                Map.of("question", QUESTION))));
        r.setStopReason("tool_use");
        return r;
    }

    @Test
    @DisplayName("the run's question is its reply")
    void questionIsTheReply() {
        turns.add(askTurn());
        AgentRunRequest req = new AgentRunRequest("sales report for last month", null, null, null, 10, "key", null, null);

        AgentRunResponse r = service.run(req, "run-1", ActivityListener.NOOP);

        assertThat(r.status()).isEqualTo("CLARIFICATION_NEEDED");
        assertThat(r.result()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("the chat widget is shown the question, not 'I couldn't generate a response'")
    void chatShowsTheQuestion() throws Exception {
        turns.add(askTurn());
        ChatRunService chat = new ChatRunService(service, new AgentStreamRegistry());
        AgentRunRequest req = new AgentRunRequest("sales report for last month", null, null, null, 10, "key", null, null);

        String runId = chat.start(req);
        Map<String, Object> body = null;
        for (int i = 0; i < 200; i++) {
            body = chat.status(runId).orElseThrow();
            if (!"RUNNING".equals(body.get("status"))) break;
            Thread.sleep(25);
        }

        assertThat(body).containsEntry("status", "DONE").containsEntry("reply", QUESTION);
    }

    @Test
    @DisplayName("every other outcome reads as before")
    void otherOutcomesUnchanged() {
        assertThat(AgentService.replyOf(AgentResult.error("boom", 0, 0))).isEqualTo("boom");
        assertThat(AgentService.replyOf(AgentResult.needsClarification(QUESTION, 0, 0, 0, 0))).isEqualTo(QUESTION);
    }
}
