package com.vortox.sidecar.service;

import com.vortox.agent.AnthropicClient;
import com.vortox.agent.LlmClient;
import com.vortox.agent.spi.ToolExecutor;
import com.vortox.agent.spi.ToolGate;
import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillRegistry;
import com.vortox.agent.spi.ActivityListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A run that reaches a tool needing approval stops and says what it is waiting for; a decision
 * resumes the same run, and only an approval makes the call. The person's model key is not kept
 * while the run waits — the decision brings it again.
 */
class AgentServiceApprovalTest {

    private final Deque<AnthropicClient.ClaudeResponse> turns = new ArrayDeque<>();
    private final List<String> keysSeen = new ArrayList<>();
    private final List<Map<String, Object>> executed = new ArrayList<>();
    private AgentService service;

    private final LlmClient llm = new LlmClient() {
        @Override
        public AnthropicClient.ClaudeResponse send(String systemPrompt, List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools, String apiKey, String model) {
            keysSeen.add(apiKey);
            return turns.poll();
        }
    };

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        SkillRegistry registry = new SkillRegistry();
        ReflectionTestUtils.setField(registry, "skillsPath", tmp.resolve("skills").toString());
        ReflectionTestUtils.setField(registry, "allowLocalSkills", true);
        registry.save("declare", "name: declare\ndescription: d\nlanguage: python\ntimeout_seconds: 30\n"
                + "requires_approval: \"Declare on your behalf: purpose {purpose}\"\n"
                + "input_schema:\n  type: object\n"
                + "implementation: |\n  print('x')\n");

        ScriptToolExecutor scripts = mock(ScriptToolExecutor.class);
        ToolExecutor recording = (name, params, runId) -> {
            executed.add(params);
            return "{\"recorded\":true}";
        };
        when(scripts.forTenant(any())).thenReturn(recording);

        service = new AgentService(registry, scripts) {
            @Override
            LlmClient resolveLlmClient(String provider, String baseUrl) { return llm; }
        };
        ReflectionTestUtils.setField(service, "defaultModel", "m");
        ReflectionTestUtils.setField(service, "defaultMaxTokens", 1000);
    }

    private static AnthropicClient.ClaudeResponse declareTurn() {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.toolUse("d1", "declare", Map.of("purpose", "BILLING"))));
        r.setStopReason("tool_use");
        return r;
    }

    private static AnthropicClient.ClaudeResponse answer(String text) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(AnthropicClient.ContentBlock.text(text)));
        r.setStopReason("end_turn");
        return r;
    }

    private AgentRunResponse pause() {
        turns.add(declareTurn());
        AgentRunRequest req = new AgentRunRequest("read the contacts", null, null, null, 10, "key-1", null, null);
        AgentRunResponse r = service.run(req, "run-1", ActivityListener.NOOP);
        assertThat(r.status()).isEqualTo("APPROVAL_NEEDED");
        assertThat(r.result()).isEqualTo("Declare on your behalf: purpose BILLING");
        assertThat(executed).isEmpty();
        return r;
    }

    @Test
    @DisplayName("approved: the same run continues, the call is made once, with the key the decision brought")
    void approve() {
        pause();
        turns.add(answer("Declared. Here are the rows."));

        AgentRunResponse r = service.resume("run-1", true, null, "key-2", ActivityListener.NOOP);

        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(r.result()).isEqualTo("Declared. Here are the rows.");
        assertThat(executed).containsExactly(Map.of("purpose", "BILLING"));
        assertThat(keysSeen).containsExactly("key-1", "key-2");
        assertThat(r.toolCalls()).extracting(AgentRunResponse.ToolCallDto::toolName).containsExactly("declare");
    }

    @Test
    @DisplayName("rejected: the call is never made and the run goes on without it")
    void reject() {
        pause();
        turns.add(answer("Understood."));

        AgentRunResponse r = service.resume("run-1", false, "wrong purpose", "key-2", ActivityListener.NOOP);

        assertThat(r.status()).isEqualTo("SUCCESS");
        assertThat(executed).isEmpty();
    }

    @Test
    @DisplayName("a decision is used once: a second one finds nothing waiting")
    void decidedOnce() {
        pause();
        turns.add(answer("ok"));
        service.resume("run-1", true, null, "key-2", ActivityListener.NOOP);

        AgentRunResponse again = service.resume("run-1", true, null, "key-2", ActivityListener.NOOP);
        assertThat(again.status()).isEqualTo("FAILED");
        assertThat(executed).hasSize(1);
    }

    @Test
    @DisplayName("nothing waiting, or waited too long: refused, nothing runs")
    void unknownOrExpired() {
        assertThat(service.resume("nope", true, null, "k", ActivityListener.NOOP).status()).isEqualTo("FAILED");

        pause();
        service.pendingApprovals().expireAll();
        AgentRunResponse late = service.resume("run-1", true, null, "key-2", ActivityListener.NOOP);
        assertThat(late.status()).isEqualTo("FAILED");
        assertThat(late.result()).contains("expired");
        assertThat(executed).isEmpty();
    }

    @Test
    @DisplayName("the waiting run does not keep the person's key")
    void keyNotKept() {
        pause();
        assertThat(service.pendingApprovals().peek("run-1")).get()
                .satisfies(p -> assertThat(p.request().apiKey()).isNull());
    }

    @Test
    @DisplayName("a host-supplied gate holds calls too")
    void hostGate() throws Exception {
        ReflectionTestUtils.setField(service, "hostGates",
                List.<ToolGate>of((name, params) -> "declare".equals(name) ? "host text" : null));
        // The skill's own declaration is checked first; with it present, its text wins.
        turns.add(declareTurn());
        AgentRunResponse r = service.run(new AgentRunRequest("t", null, null, null, 10, "k", null, null),
                "run-2", ActivityListener.NOOP);
        assertThat(r.status()).isEqualTo("APPROVAL_NEEDED");
        assertThat(r.result()).isEqualTo("Declare on your behalf: purpose BILLING");
    }
}
