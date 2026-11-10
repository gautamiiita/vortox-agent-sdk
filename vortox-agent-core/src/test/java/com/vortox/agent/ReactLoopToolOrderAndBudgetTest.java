package com.vortox.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writes and commands in one reply run one at a time; reads still run side by side (2026-11-10). And a
 * run stops at its spending cap. Both are off unless the host asks, so a host that sets neither sees
 * the loop it always had.
 */
class ReactLoopToolOrderAndBudgetTest {

    private static final Set<String> READS = Set.of("read_file", "grep");
    private static final Predicate<String> READS_ALONGSIDE = READS::contains;

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @AfterEach
    void stop() { pool.shutdownNow(); }

    /** Records how many tools were running at once, and the order they started in. */
    static final class Recorder {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final AtomicInteger writePeak = new AtomicInteger();
        final AtomicInteger writesRunning = new AtomicInteger();
        final List<String> started = Collections.synchronizedList(new ArrayList<>());

        String run(String name, Map<String, Object> params) {
            started.add(name + ":" + params.get("id"));
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            boolean write = !READS.contains(name);
            if (write) writePeak.accumulateAndGet(writesRunning.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
                if (write) writesRunning.decrementAndGet();
            }
            return name + " done";
        }
    }

    private static AnthropicClient.ClaudeResponse reply(String stop, int in, AnthropicClient.ContentBlock... blocks) {
        AnthropicClient.ClaudeResponse r = new AnthropicClient.ClaudeResponse();
        r.setContent(List.of(blocks));
        r.setStopReason(stop);
        r.setInputTokens(in);
        r.setOutputTokens(100);
        return r;
    }

    private static AnthropicClient.ContentBlock call(String id, String name) {
        return AnthropicClient.ContentBlock.toolUse(id, name, Map.of("id", id));
    }

    private AgentResult run(ReactLoopEmptyReplyTest.Scripted client, Recorder rec,
                            Predicate<String> rule, BudgetGuard guard) {
        AgentConfig.Builder b = AgentConfig.builder().agentId("a").apiKey("k").maxIterations(10)
                .toolExecutor((name, params, runId) -> rec.run(name, params));
        if (rule != null) b.concurrentTools(rule);
        if (guard != null) b.budgetGuard(guard);
        return new ReactLoop(b.build(), client, pool).run("Do it.", null, "run-1", null, null, "t", null);
    }

    private static ReactLoopEmptyReplyTest.Scripted script(AnthropicClient.ClaudeResponse... replies) {
        ReactLoopEmptyReplyTest.Scripted c = new ReactLoopEmptyReplyTest.Scripted();
        c.turns.addAll(List.of(replies));
        return c;
    }

    @Test
    @DisplayName("with a rule: writes and commands never overlap, and run in the order asked")
    void writesRunOneAtATime() {
        Recorder rec = new Recorder();
        AgentResult r = run(script(
                        reply("tool_use", 10, call("1", "edit_file"), call("2", "edit_file"), call("3", "run_tests")),
                        reply("end_turn", 10, AnthropicClient.ContentBlock.text("Done."))),
                rec, READS_ALONGSIDE, null);

        assertTrue(r.isSuccess(), String.valueOf(r.status()));
        assertEquals(1, rec.writePeak.get(), "two writes ran at the same time");
        assertEquals(List.of("edit_file:1", "edit_file:2", "run_tests:3"), rec.started);
    }

    @Test
    @DisplayName("with a rule: reads still run together, and a write waits for the reads before it")
    void readsStillRunTogether() {
        Recorder rec = new Recorder();
        ReactLoopEmptyReplyTest.Scripted client = script(
                reply("tool_use", 10, call("1", "read_file"), call("2", "grep"), call("3", "read_file"),
                        call("4", "write_file"), call("5", "read_file")),
                reply("end_turn", 10, AnthropicClient.ContentBlock.text("Done.")));
        run(client, rec, READS_ALONGSIDE, null);

        assertEquals(3, rec.peak.get(), "the three reads before the write ran together");
        assertEquals("write_file:4", rec.started.get(3), "the write started after the reads before it");
        // The model gets one result per call, in the order it asked for them.
        List<Map<String, Object>> second = client.requests.get(1);
        String results = String.valueOf(second.get(second.size() - 1).get("content"));
        assertTrue(results.indexOf("tool_use_id=1") < results.indexOf("tool_use_id=4")
                && results.indexOf("tool_use_id=4") < results.indexOf("tool_use_id=5"), results);
    }

    @Test
    @DisplayName("without a rule every call runs in parallel, as before")
    void noRuleKeepsTheOldBehaviour() {
        Recorder rec = new Recorder();
        run(script(
                        reply("tool_use", 10, call("1", "edit_file"), call("2", "edit_file"), call("3", "run_tests")),
                        reply("end_turn", 10, AnthropicClient.ContentBlock.text("Done."))),
                rec, null, null);

        assertEquals(3, rec.peak.get());
    }

    @Test
    @DisplayName("batches: consecutive reads share one, anything else is alone; a rule that throws runs the call alone")
    void batching() {
        Predicate<String> rule = n -> {
            if (n.equals("boom")) throw new IllegalStateException();
            return READS.contains(n);
        };
        assertEquals(List.of(List.of("read_file", "grep"), List.of("edit_file"), List.of("boom"), List.of("read_file")),
                ReactLoop.<String>batches(List.of("read_file", "grep", "edit_file", "boom", "read_file"), n -> n, rule));
        assertEquals(List.of(List.of("a", "b")), ReactLoop.<String>batches(List.of("a", "b"), n -> n, null));
        assertEquals(List.of(), ReactLoop.<String>batches(List.<String>of(), n -> n, rule));
    }

    @Test
    @DisplayName("a run over its spending cap stops as PARTIAL before its tools run, with its conversation")
    void stopsAtTheCap() {
        Recorder rec = new Recorder();
        List<BudgetGuard.Spend> seen = new ArrayList<>();
        BudgetGuard capAt1000 = spend -> {
            seen.add(spend);
            return spend.input() >= 1000 ? "spent 1000 tokens of 1000" : null;
        };
        AgentResult r = run(script(
                        reply("tool_use", 600, call("1", "read_file")),
                        reply("tool_use", 600, call("2", "edit_file")),
                        reply("end_turn", 10, AnthropicClient.ContentBlock.text("Done."))),
                rec, READS_ALONGSIDE, capAt1000);

        assertEquals(AgentResult.Status.PARTIAL, r.status());
        assertTrue(r.response().contains("spending cap"), r.response());
        assertEquals(List.of("read_file:1"), rec.started, "the reply that crossed the cap ran nothing");
        assertEquals(2, seen.size());
        assertEquals(1200, seen.get(1).input());
        assertNotNull(r.conversationHistory());
        assertFalse(r.conversationHistory().isEmpty(), "the conversation is kept, so the run can resume");
    }

    @Test
    @DisplayName("a cap that fails to answer never stops a run")
    void aBrokenCapIsNoCap() {
        Recorder rec = new Recorder();
        AgentResult r = run(script(
                        reply("tool_use", 600, call("1", "read_file")),
                        reply("end_turn", 10, AnthropicClient.ContentBlock.text("Done."))),
                rec, READS_ALONGSIDE, spend -> { throw new IllegalStateException("pricing down"); });

        assertTrue(r.isSuccess(), String.valueOf(r.status()));
    }
}
