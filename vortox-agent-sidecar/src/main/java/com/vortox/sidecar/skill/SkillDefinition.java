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
        String implementation
) {

    /** Converts this skill into the tool definition format expected by the Anthropic API. */
    public Map<String, Object> toToolDefinition() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", inputSchema != null ? inputSchema : Map.of("type", "object", "properties", Map.of())
        );
    }
}
