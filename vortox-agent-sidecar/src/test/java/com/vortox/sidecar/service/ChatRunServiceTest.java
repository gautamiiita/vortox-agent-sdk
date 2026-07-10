package com.vortox.sidecar.service;

import com.vortox.sidecar.api.AgentRunRequest;
import com.vortox.sidecar.api.AgentRunResponse;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the runId mismatch this feature fixes: before, {@link ChatRunService}
 * minted a runId that it returned/polled under, while {@link AgentService} silently minted a
 * *different* one internally — so a stream keyed by ChatRunService's runId would never see any
 * events, because ReactLoop actually fired them under AgentService's own id.
 */
class ChatRunServiceTest {

    private static final AgentRunRequest REQUEST =
            new AgentRunRequest("do something", null, null, null, null, null, null, null);

    @Test
    void usesTheSameRunIdItReturnsWhenCallingAgentService() throws Exception {
        AgentService agentService = mock(AgentService.class);
        AgentStreamRegistry streamRegistry = new AgentStreamRegistry();
        ChatRunService chatRunService = new ChatRunService(agentService, streamRegistry);

        when(agentService.run(any(), anyString(), any())).thenAnswer(invocation -> {
            String runIdSeenByAgentService = invocation.getArgument(1);
            return new AgentRunResponse(runIdSeenByAgentService, "SUCCESS", "done",
                    List.of(), 10, 20, List.of());
        });

        String runId = chatRunService.start(REQUEST);
        awaitTerminalStatus(chatRunService, runId);

        verify(agentService).run(eq(REQUEST), eq(runId), any());

        Map<String, Object> status = chatRunService.status(runId).orElseThrow();
        assertThat(status.get("status")).isEqualTo("DONE");
        assertThat(status.get("reply")).isEqualTo("done");
    }

    @Test
    void completesTheStreamEvenWhenAgentServiceThrows() throws Exception {
        AgentService agentService = mock(AgentService.class);
        AgentStreamRegistry streamRegistry = new AgentStreamRegistry();
        ChatRunService chatRunService = new ChatRunService(agentService, streamRegistry);

        when(agentService.run(any(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));

        String runId = chatRunService.start(REQUEST);
        awaitTerminalStatus(chatRunService, runId);

        Map<String, Object> status = chatRunService.status(runId).orElseThrow();
        assertThat(status.get("status")).isEqualTo("ERROR");

        // The stream reader must be released (not left hanging) despite the exception —
        // this is exactly what the finally-block fix in ChatRunService.start() guarantees.
        AgentStreamRegistry.Evt evt = streamRegistry.poll(runId, 1000);
        assertThat(streamRegistry.isDone(evt)).isTrue();
    }

    private static void awaitTerminalStatus(ChatRunService chatRunService, String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            Optional<Map<String, Object>> status = chatRunService.status(runId);
            if (status.isPresent() && !"RUNNING".equals(status.get().get("status"))) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Run " + runId + " never left RUNNING within the test deadline");
    }
}
