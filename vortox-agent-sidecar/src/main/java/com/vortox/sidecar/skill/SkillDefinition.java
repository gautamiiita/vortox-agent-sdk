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
        ProducesArtifact producesArtifact
) {

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
