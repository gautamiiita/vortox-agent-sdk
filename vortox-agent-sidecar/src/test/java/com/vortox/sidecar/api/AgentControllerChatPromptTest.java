package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.service.AgentStreamRegistry;
import com.vortox.sidecar.service.ChatRunService;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the chat endpoint puts in which field of the run it starts.
 *
 * <p>The distinction matters downstream: {@code AgentService} refuses a caller-supplied
 * {@code systemPrompt} for runs linked to a Vortox agent, but always appends
 * {@code runtimeInstructions}. Page-action text landing in the wrong one is the difference between
 * describing the browser's capabilities and quietly replacing the agent's persona.
 */
class AgentControllerChatPromptTest {

    private final ChatRunService chatRunService = mock(ChatRunService.class);

    private AgentRunRequest runStartedBy(AgentChatRequest request) {
        when(chatRunService.start(any())).thenReturn("run-1");

        AgentController controller = new AgentController(
                mock(AgentService.class), mock(SkillRegistry.class),
                chatRunService, mock(AgentStreamRegistry.class));
        controller.chat(request);

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(chatRunService).start(captor.capture());
        return captor.getValue();
    }

    private static AgentChatRequest chat(String systemPrompt, List<String> pageActions, String surface) {
        return new AgentChatRequest("what is this order?", null, null, null, null,
                pageActions, null, systemPrompt, null, null, null, null, null, surface);
    }

    @Test
    void pageActionInstructionsGoToRuntimeInstructionsNotThePersona() {
        AgentRunRequest run = runStartedBy(chat(null, List.of("highlight", "fill"), null));

        assertThat(run.runtimeInstructions()).contains("## Page Actions").contains("highlight");
        assertThat(run.systemPrompt()).isNull();
    }

    @Test
    void aRequestWithNeitherPersonaNorActionsLeavesBothFieldsEmpty() {
        AgentRunRequest run = runStartedBy(chat(null, null, null));

        assertThat(run.systemPrompt()).isNull();
        assertThat(run.runtimeInstructions()).isNull();
    }

    /**
     * Passed through rather than dropped — refusing it is {@code AgentService}'s decision to make,
     * and only it knows whether this run is linked to an agent that owns the prompt.
     */
    @Test
    void aCallerSuppliedPersonaIsRelayedInItsOwnField() {
        AgentRunRequest run = runStartedBy(chat("you are a pirate", List.of("highlight"), null));

        assertThat(run.systemPrompt()).isEqualTo("you are a pirate");
        assertThat(run.runtimeInstructions()).doesNotContain("pirate").contains("## Page Actions");
    }

    @Test
    void surfaceIsCarriedIntoTheRun() {
        assertThat(runStartedBy(chat(null, null, "tnam:order-detail")).surface())
                .isEqualTo("tnam:order-detail");
    }
}
