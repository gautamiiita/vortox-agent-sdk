package com.vortox.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Notices a run sending the same failing tool call over and over, and says so.
 *
 * <p>Seen in production (Pantry, 2026-09-23): replies cut off at a 2048-token output cap reached the
 * tools with their arguments missing, the tools answered "content is required", and the model — told
 * nothing about why — sent the same empty call about seventy times. That run spent half of its 142
 * iterations there, another 169 of 241. The cut-off itself is now explained ({@link
 * ReactLoop#TRUNCATED_TOOL_CALL}), but the shape — an identical call failing identically, again and
 * again — can come from anywhere: a path that does not exist, a tool that is broken, a command the
 * sandbox refuses. Repeating it is never going to work, so the loop should not pay for it silently.
 *
 * <p>"Identical" means the same tool, the same arguments and the same error. Different arguments are
 * a different attempt, however similar, and are left alone. The count is per run, not per streak: a
 * probe that succeeds in between does not make the next identical failure a fresh one.
 *
 * <p>At {@link #WARN_AT} the failure is annotated with a plain instruction to change course. At
 * {@link #STOP_AT} the run ends with a readable reason instead of burning its remaining budget — the
 * threshold is set high enough that a model polling something that is still starting has room.
 */
final class RepeatedFailureGuard {

    static final int WARN_AT = 3;
    static final int STOP_AT = 8;

    private final Map<String, Integer> failures = new HashMap<>();
    private String stopReason;

    /**
     * Records one finished call.
     *
     * @return how many times this exact call has now failed this way; 0 for a success
     */
    int record(String tool, Map<String, Object> input, boolean success, String result) {
        if (success) return 0;
        int n = failures.merge(signature(tool, input, result), 1, Integer::sum);
        if (n >= STOP_AT && stopReason == null) {
            stopReason = "Stopped: `" + tool + "` failed the same way " + n + " times with the same arguments — "
                    + firstLine(result);
        }
        return n;
    }

    /** Why the run should stop now, or null while it may continue. */
    String stopReason() {
        return stopReason;
    }

    /** The result the model sees for a failure that has now repeated {@code count} times. */
    static String annotate(String result, int count) {
        if (count < WARN_AT) return result;
        return (result == null ? "" : result)
                + "\n\n[Loop guard] This exact call has now failed the same way " + count + " times. "
                + "Sending it again unchanged will not work. Change the arguments or take a different "
                + "approach; if you are waiting for something to start, check something else before "
                + "trying again. If you cannot proceed, call request_clarification.";
    }

    static String signature(String tool, Map<String, Object> input, String result) {
        // The arguments by hash: a write_file's content can be large, and only equality matters.
        String args = input == null ? "" : String.valueOf(new java.util.TreeMap<>(input));
        String error = result == null ? "" : result.strip();
        if (error.length() > 300) error = error.substring(0, 300);
        return tool + '\u0000' + args.length() + ':' + args.hashCode() + '\u0000' + error;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "no error text";
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
