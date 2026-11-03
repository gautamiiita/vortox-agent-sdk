package com.vortox.sidecar.service;

import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chat run that stops for approval is a state the widget can see and answer — not a reply. It
 * shows what is being asked; one decision resumes it; anything else is refused without effect.
 */
class ChatRunServiceApprovalTest {

    private static final AgentRunRequest REQUEST =
            new AgentRunRequest("read the contacts", null, null, null, null, null, null, null);

    private AgentService agentService;
    private ChatRunService chat;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        chat = new ChatRunService(agentService, new AgentStreamRegistry());
        when(agentService.run(any(), anyString(), any())).thenAnswer(inv -> new AgentRunResponse(
                inv.getArgument(1), "APPROVAL_NEEDED", "Declare on your behalf: purpose BILLING",
                List.of(), 1, 1, List.of()));
    }

    private String waitingRun() throws InterruptedException {
        String runId = chat.start(REQUEST);
        awaitStatus(runId, "APPROVAL_NEEDED");
        return runId;
    }

    @Test
    @DisplayName("the poll says it is waiting, and for what — it is not a reply")
    void waitingIsVisible() throws Exception {
        String runId = waitingRun();
        Map<String, Object> s = chat.status(runId).orElseThrow();
        assertThat(s.get("approval")).isEqualTo("Declare on your behalf: purpose BILLING");
        assertThat(s).doesNotContainKey("reply");
    }

    @Test
    @DisplayName("a decision resumes the same run with that decision and the key it brought")
    void decisionResumes() throws Exception {
        when(agentService.resume(anyString(), eq(true), any(), any(), any())).thenAnswer(inv ->
                new AgentRunResponse(inv.getArgument(0), "SUCCESS", "Declared.", List.of(), 1, 1, List.of()));
        String runId = waitingRun();

        assertThat(chat.decide(runId, true, "ok", "key-2")).isEqualTo(ChatRunService.Decision.ACCEPTED);
        awaitStatus(runId, "DONE");

        verify(agentService).resume(eq(runId), eq(true), eq("ok"), eq("key-2"), any());
        assertThat(chat.status(runId).orElseThrow().get("reply")).isEqualTo("Declared.");
    }

    @Test
    @DisplayName("a run that is not waiting cannot be decided; an unknown one is not found")
    void refusals() throws Exception {
        when(agentService.resume(anyString(), any(Boolean.class), any(), any(), any())).thenAnswer(inv ->
                new AgentRunResponse(inv.getArgument(0), "SUCCESS", "done", List.of(), 1, 1, List.of()));
        String runId = waitingRun();
        assertThat(chat.decide(runId, true, null, null)).isEqualTo(ChatRunService.Decision.ACCEPTED);
        assertThat(chat.decide(runId, true, null, null)).isEqualTo(ChatRunService.Decision.NOT_WAITING);
        assertThat(chat.decide("unknown", true, null, null)).isEqualTo(ChatRunService.Decision.NOT_FOUND);
        awaitStatus(runId, "DONE");
        verify(agentService).resume(eq(runId), eq(true), any(), any(), any());
    }

    @Test
    @DisplayName("a resume the service refuses (expired) ends the run as an error")
    void expiredResume() throws Exception {
        when(agentService.resume(anyString(), any(Boolean.class), any(), any(), any())).thenAnswer(inv ->
                new AgentRunResponse(inv.getArgument(0), "FAILED",
                        "The approval request expired.", List.of(), 0, 0, List.of()));
        String runId = waitingRun();
        chat.decide(runId, true, null, "k");
        awaitStatus(runId, "ERROR");
        assertThat(chat.status(runId).orElseThrow().get("error")).isEqualTo("The approval request expired.");
    }

    @Test
    @DisplayName("a run never decided does not resume by itself")
    void noDecisionNoResume() throws Exception {
        waitingRun();
        verify(agentService, never()).resume(anyString(), any(Boolean.class), any(), any(), any());
    }

    private void awaitStatus(String runId, String want) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            Optional<Map<String, Object>> s = chat.status(runId);
            if (s.isPresent() && want.equals(s.get().get("status"))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("run " + runId + " never reached " + want + ": " + chat.status(runId));
    }
}
