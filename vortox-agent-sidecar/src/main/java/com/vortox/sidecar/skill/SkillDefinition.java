package com.vortox.sidecar.skill;

import java.util.Map;

/**
 * Parsed representation of a SKILL.md file.
 * Skills live in /app/skills/{skill-name}/SKILL.md and are pure YAML.
 */
public record SkillDefinition(
        String name,
        String description,
        String language,
        int timeoutSeconds,
        Map<String, Object> inputSchema,
        String implementation,
        String rawContent,
        ProducesArtifact producesArtifact,
        /*
         * Declares that a call of this tool waits for a person's approval, and what they read:
         * {@code requires_approval: "Declare on your behalf: {resource}"} — each {@code {field}} is
         * filled from the call's input. {@code true} asks with a default text (stored as ""), and
         * null — absent or {@code false} — means the tool runs without asking. Never sent to the LLM.
         */
        String requiresApproval
) {

    /** The shape before approvals existed: a tool that never asks. */
    public SkillDefinition(String name, String description, String language, int timeoutSeconds,
                           Map<String, Object> inputSchema, String implementation, String rawContent,
                           ProducesArtifact producesArtifact) {
        this(name, description, language, timeoutSeconds, inputSchema, implementation, rawContent,
                producesArtifact, null);
    }

    /**
     * Declares that a successful call of this skill writes a file worth offering the caller as a
     * download, and where to find its path: {@code pathField} is a key in the tool call's JSON
     * input (the caller told the skill where to write) or output (the skill decided and reported
     * back), per {@code source} ({@code "input"} or {@code "output"}).
     * <p>
     * Declared in SKILL.md as:
     * <pre>{@code
     * produces_artifact:
     *   path_field: csv_path
     *   source: output   # or "input"; defaults to "output" if omitted
     * }</pre>
     */
    public record ProducesArtifact(String pathField, String source) {
        public boolean isInputSourced() { return "input".equalsIgnoreCase(source); }
    }

    /** Converts this skill into the tool definition format expected by the Anthropic API. */
    public Map<String, Object> toToolDefinition() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of())
        );
    }
}
