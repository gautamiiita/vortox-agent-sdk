package com.vortox.agent;

import java.util.List;
import java.util.Map;

/**
 * The text of a clarification question, laid out so a person can answer it at a glance (2026-10-18).
 *
 * <p>An agent's question used to be one free-text field. Agents put their whole reasoning in it —
 * task 973's ran to 900 words, with the actual decision and its three options in the last
 * paragraph — and the board showed it as one block. The tool now takes the question, the options
 * and the explanation apart, and they are stored as one text in a fixed layout:
 *
 * <pre>
 * Which do you want?
 *
 * Options:
 * (a) Accept the run as evidence
 * (b) Re-run with app_preview
 *
 * Details:
 * Requirement 23 cannot be met because …
 * </pre>
 *
 * Readable as it is wherever the question is shown (a notification, the activity log), and parsed
 * by the dashboard into a question, option buttons and collapsible details. One text, so nothing
 * that stores or forwards the question has to change.
 */
public final class Clarification {

    private Clarification() {}

    public static final String OPTIONS_HEADING = "Options:";
    public static final String DETAILS_HEADING = "Details:";
    private static final int MAX_OPTIONS = 6;

    /** The question laid out from a tool call's input: {@code question}, optional {@code options} and {@code context}. */
    public static String text(Map<String, Object> input, String fallback) {
        Object q = input == null ? null : input.get("question");
        String question = q == null || String.valueOf(q).isBlank() ? fallback : String.valueOf(q).strip();
        List<String> options = input != null && input.get("options") instanceof List<?> l
                ? l.stream().filter(o -> o != null && !String.valueOf(o).isBlank()).map(o -> String.valueOf(o).strip()).toList()
                : List.of();
        Object c = input == null ? null : input.get("context");
        return text(question, options, c == null ? null : String.valueOf(c));
    }

    public static String text(String question, List<String> options, String context) {
        StringBuilder sb = new StringBuilder(question == null ? "" : question.strip());
        if (options != null && !options.isEmpty()) {
            sb.append("\n\n").append(OPTIONS_HEADING);
            for (int i = 0; i < Math.min(options.size(), MAX_OPTIONS); i++) {
                sb.append("\n(").append((char) ('a' + i)).append(") ").append(options.get(i).replaceAll("\\s*\\n\\s*", " "));
            }
        }
        if (context != null && !context.isBlank()) sb.append("\n\n").append(DETAILS_HEADING).append('\n').append(context.strip());
        return sb.toString();
    }

    /** The tool's input schema: the question, and optionally the choices and the explanation, kept apart. */
    public static Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "question", Map.of("type", "string", "description",
                                "The decision you need, as ONE short question — e.g. \"Requirement 23 can't be met "
                                + "here. Which do you want?\". Not your reasoning: that goes in context."),
                        "options", Map.of("type", "array", "items", Map.of("type", "string"), "description",
                                "The answers you would accept, one short line each, when the choice is between a few "
                                + "known options (at most 6). The person picks one or writes their own."),
                        "context", Map.of("type", "string", "description",
                                "What the person needs to decide well: what you found, what you tried, what each "
                                + "option means. Markdown; use headings and lists.")),
                "required", List.of("question"));
    }

    /** What the tool's description adds about laying the question out. */
    public static final String HOW_TO_ASK =
            " Put the decision in question (one sentence), the choices in options, and your explanation in context — "
            + "the person sees the question and the choices first.";
}
