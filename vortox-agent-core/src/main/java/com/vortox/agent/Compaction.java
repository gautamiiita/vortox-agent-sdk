package com.vortox.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * COMPACTION: when a conversation nears the context window, it is replaced by the agent's own summary
 * of it, and the run continues from that.
 *
 * <p>This replaced pruning (2026-10-16). Pruning shortened old tool results in place every time the
 * conversation passed ~320k characters, and each pass rewrote the cached conversation: task 944 paid for
 * ~130k-token cache writes at turns 34 and 79 of one run. Nothing is removed now until the context is
 * near its limit — every turn only appends, so every turn reads the whole conversation from the cache —
 * and then once, as a summary, the way Claude Code continues a long session.
 *
 * <p>The summary is written by the same model, from the same cached conversation, in a turn of its own:
 * one cheap cache read and a few thousand output tokens. The run then continues from its original task
 * message with the summary appended; tools it had loaded are surfaced again.
 */
final class Compaction {

    private Compaction() {}

    static final String REQUEST = "[Vortox] This conversation is about to be compacted: everything above will be "
            + "replaced by the summary you write now, and you will continue the task from that summary alone. "
            + "Reply with the summary as plain text — do not call any tool. Include:\n"
            + "1. The task, and every requirement that is not yet met.\n"
            + "2. What you have done: each file you changed or created and what changed in it, commits made.\n"
            + "3. The current state: the last build and test results, errors not yet fixed, work in progress.\n"
            + "4. What you established that you would otherwise have to find again: paths, commands that work, "
            + "how the code is organised, decisions taken and why, things that did not work.\n"
            + "5. Your next steps, in order.\n"
            + "Be specific: names, paths, numbers. Leave out what no longer matters.";

    static final String CONTINUE_NOTE = "[Vortox] Your conversation on this task was compacted to stay within the "
            + "context window. Your own summary of it follows: continue the task from there. The files on disk are "
            + "as you left them — read a file again only when you need its current content.\n\n## Summary of your work so far\n\n";

    /** Whether a conversation of {@code contextTokens} has reached the threshold (0 = never). */
    static boolean due(long contextTokens, int compactAtTokens) {
        return compactAtTokens > 0 && contextTokens >= compactAtTokens;
    }

    /** The conversation with the summary request added to its last user turn — a copy; the run's list is untouched. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> summaryRequest(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages);
        int at = out.size() - 1;
        while (at >= 0 && !"user".equals(out.get(at).get("role"))) at--;
        Map<String, Object> request = Map.of("type", "text", "text", REQUEST);
        if (at < 0 || "assistant".equals(out.get(out.size() - 1).get("role"))) {
            out.add(Map.of("role", "user", "content", List.of(request)));
            return out;
        }
        Map<String, Object> user = new LinkedHashMap<>(out.get(at));
        List<Object> blocks = new ArrayList<>();
        Object content = user.get("content");
        if (content instanceof String s) blocks.add(Map.of("type", "text", "text", s));
        else if (content instanceof List<?> l) blocks.addAll(l);
        blocks.add(request);
        user.put("content", blocks);
        out.set(at, user);
        return out;
    }

    /**
     * The conversation to continue with: the run's first user message with the summary appended, and the
     * tools it had loaded surfaced again.
     */
    static List<Map<String, Object>> compacted(Map<String, Object> firstUser, String summary,
                                               Collection<String> surfacedTools) {
        List<Object> blocks = new ArrayList<>();
        Object content = firstUser != null ? firstUser.get("content") : null;
        if (content instanceof String s) blocks.add(Map.of("type", "text", "text", s));
        else if (content instanceof List<?> l) {
            // Only the task's own text and attachments: never a tool_result, which would answer nothing now.
            for (Object b : l) if (!(b instanceof Map<?, ?> m && "tool_result".equals(m.get("type")))) blocks.add(b);
        }
        blocks.add(Map.of("type", "text", "text", CONTINUE_NOTE + summary.strip()));
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", blocks);
        out.add(user);
        if (surfacedTools != null && !surfacedTools.isEmpty()) out.add(ToolLoading.additionMessage(surfacedTools));
        return out;
    }
}
