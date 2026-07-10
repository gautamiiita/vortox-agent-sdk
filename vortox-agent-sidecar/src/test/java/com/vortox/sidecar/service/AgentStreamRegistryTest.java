package com.vortox.sidecar.service;

import com.vortox.agent.AgentResult;
import com.vortox.agent.spi.ActivityListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamRegistryTest {

    @Test
    void publishesEventsInOrderWithExpectedShape() throws Exception {
        AgentStreamRegistry registry = new AgentStreamRegistry();
        ActivityListener listener = registry.listenerFor("run-1");

        listener.onIteration("run-1", 1, 75, "LLM call");
        listener.onToolCall("run-1", "file_read", Map.of("path", "x"));
        listener.onToolResult("run-1", "file_read", true, 42L);

        AgentStreamRegistry.Evt iteration = registry.poll("run-1", 1000);
        assertThat(iteration.name()).isEqualTo("iteration");
        assertThat(iteration.json()).contains("\"iteration\":1").contains("\"maxIterations\":75");

        AgentStreamRegistry.Evt toolCall = registry.poll("run-1", 1000);
        assertThat(toolCall.name()).isEqualTo("tool_call");
        assertThat(toolCall.json()).contains("\"tool\":\"file_read\"");

        AgentStreamRegistry.Evt toolResult = registry.poll("run-1", 1000);
        assertThat(toolResult.name()).isEqualTo("tool_result");
        assertThat(toolResult.json()).contains("\"success\":true").contains("\"durationMs\":42");
    }

    @Test
    void completeUnblocksAWaitingPoll() throws Exception {
        AgentStreamRegistry registry = new AgentStreamRegistry();
        registry.listenerFor("run-2");

        AtomicReference<AgentStreamRegistry.Evt> received = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            try {
                received.set(registry.poll("run-2", 5000));
            } catch (InterruptedException ignored) {
            } finally {
                done.countDown();
            }
        });
        reader.start();

        // Give the reader a moment to actually block in poll() before completing.
        Thread.sleep(100);
        registry.complete("run-2");

        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.isDone(received.get())).isTrue();
    }

    @Test
    void onCompleteCallbackIsANoOp() {
        AgentStreamRegistry registry = new AgentStreamRegistry();
        ActivityListener listener = registry.listenerFor("run-3");

        // onComplete must not throw and must not itself publish/complete — ChatRunService owns
        // signalling completion via registry.complete(runId) in its own finally block.
        listener.onComplete("run-3", AgentResult.error("boom"));
    }

    @Test
    void existsReflectsLifecycle() {
        AgentStreamRegistry registry = new AgentStreamRegistry();
        assertThat(registry.exists("run-4")).isFalse();

        registry.listenerFor("run-4");
        assertThat(registry.exists("run-4")).isTrue();

        registry.remove("run-4");
        assertThat(registry.exists("run-4")).isFalse();
    }

    @Test
    void cleanupEvictsQueuesPastTtl() throws Exception {
        AgentStreamRegistry registry = new AgentStreamRegistry();
        registry.listenerFor("run-stale");
        registry.listenerFor("run-fresh");

        // Backdate only "run-stale"'s timestamp past the registry's TTL via reflection, rather
        // than sleeping for the real 10-minute window.
        Field timestampsField = AgentStreamRegistry.class.getDeclaredField("timestamps");
        timestampsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Long> timestamps = (Map<String, Long>) timestampsField.get(registry);
        timestamps.put("run-stale", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(11));

        registry.cleanup();

        assertThat(registry.exists("run-stale")).isFalse();
        assertThat(registry.exists("run-fresh")).isTrue();
    }
}
