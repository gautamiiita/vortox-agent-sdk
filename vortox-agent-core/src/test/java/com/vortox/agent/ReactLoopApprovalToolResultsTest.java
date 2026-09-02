package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resuming a run that paused for approval must answer every tool call in the paused turn.
 *
 * <p>The Messages API requires one {@code tool_result} per {@code tool_use} in the preceding
 * assistant turn and rejects the request otherwise. The resume path used to emit exactly one, for
 * the id that paused the run — correct only when the model had asked for a single thing. It was
 * already reachable before (the model could pair {@code request_approval} with another call) and
 * became routine once a host {@link com.vortox.agent.spi.ToolGate} could hold any call: a guarded
 * agent that reads a file and writes one in the same turn pauses on the write with the read still
 * unanswered, and the resumed request would fail on {@code tool_use} ids without
 * {@code tool_result}.
 */
class ReactLoopApprovalToolResultsTest {

    private static Map<String, Object> toolUse(String id, String name) {
        return Map.of("type", "tool_use", "id", id, "name", name, "input", Map.of());
    }

    private static Map<String, Object> text(String body) {
        return Map.of("type", "text", "text", body);
    }

    private static String contentOf(Map<String, Object> result) {
        return String.valueOf(result.get("content"));
    }

    @Test
    @DisplayName("a single gated call gets the decision")
    void singleCall() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(text("I'll write the file."), toolUse("t1", "write_file")),
                "t1", "Approved by alice");

        assertEquals(1, results.size());
        assertEquals("t1", results.get(0).get("tool_use_id"));
        assertEquals("Approved by alice", contentOf(results.get(0)));
    }

    @Test
    @DisplayName("every call in the turn is answered, not just the gated one")
    void everyCallAnswered() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(toolUse("t1", "read_file"),
                        toolUse("t2", "write_file"),
                        toolUse("t3", "grep")),
                "t2", "Approved by alice");

        assertEquals(3, results.size());
        assertEquals(List.of("t1", "t2", "t3"),
                results.stream().map(r -> r.get("tool_use_id")).toList());
        assertEquals("Approved by alice", contentOf(results.get(1)));
    }

    @Test
    @DisplayName("the calls that did not run are told so, rather than given a fabricated result")
    void ungatedCallsAreToldTheTruth() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(toolUse("t1", "read_file"), toolUse("t2", "execute_command")),
                "t2", "Rejected: wrong branch");

        assertTrue(contentOf(results.get(0)).contains("Not executed"),
                "an unrun call should say it did not run: " + contentOf(results.get(0)));
        assertTrue(contentOf(results.get(0)).contains("Call this again"),
                "and should say the model may retry it");
        assertEquals("Rejected: wrong branch", contentOf(results.get(1)));
    }

    @Test
    @DisplayName("text blocks in the turn produce no tool results")
    void textBlocksIgnored() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(text("Thinking..."), toolUse("t1", "write_file"), text("Done thinking.")),
                "t1", "Approved");

        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("a snapshot with no recognisable tool_use still answers the gated id")
    void degradedSnapshotStillAnswers() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(text("no tool calls here")), "t1", "Approved");

        assertEquals(1, results.size());
        assertEquals("t1", results.get(0).get("tool_use_id"));
        assertEquals("Approved", contentOf(results.get(0)));
    }

    @Test
    @DisplayName("a null snapshot does not lose the decision")
    void nullSnapshotStillAnswers() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(null, "t1", "Approved");

        assertEquals(1, results.size());
        assertEquals("t1", results.get(0).get("tool_use_id"));
    }

    @Test
    @DisplayName("blocks without a usable id are skipped rather than emitted malformed")
    void unusableIdsSkipped() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(Map.of("type", "tool_use", "name", "write_file"),
                        toolUse("t2", "read_file")),
                "t2", "Approved");

        assertEquals(1, results.size());
        assertEquals("t2", results.get(0).get("tool_use_id"));
    }

    @Test
    @DisplayName("every result is a well-formed tool_result block")
    void resultsAreWellFormed() {
        List<Map<String, Object>> results = ReactLoop.approvalToolResults(
                List.of(toolUse("t1", "read_file"), toolUse("t2", "write_file")),
                "t2", "Approved");

        for (Map<String, Object> r : results) {
            assertEquals("tool_result", r.get("type"));
            assertInstanceOf(String.class, r.get("tool_use_id"));
            assertInstanceOf(String.class, r.get("content"));
        }
    }
}
