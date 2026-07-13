package com.vortox.sidecar.service;

import com.vortox.agent.AgentResult;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the fix for a real production issue: a skill that writes an export file as a side
 * effect of every successful call (e.g. SQL-to-CSV) previously surfaced ONE artifact per call —
 * so an agent exploring/retrying a query several times produced a wall of throwaway downloads
 * instead of just the one result that actually answered the question.
 */
class AgentServiceExtractArtifactsTest {

    private static final SkillDefinition.ProducesArtifact OUTPUT_SOURCED =
            new SkillDefinition.ProducesArtifact("csv_path", "output");

    @Test
    void keepsOnlyTheLastArtifactWhenTheSameSkillIsCalledMultipleTimes(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path first = tmp.resolve("first.csv");
        Path second = tmp.resolve("second.csv");
        Files.writeString(first, "probe-1");
        Files.writeString(second, "final-answer");

        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition oracleSkill = skillWith("oracle_to_studio", OUTPUT_SOURCED);
        when(registry.find("oracle_to_studio")).thenReturn(Optional.of(oracleSkill));

        AgentService service = new AgentService(registry, mock(ScriptToolExecutor.class));

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("oracle_to_studio", Map.of("sql", "explore columns"),
                        "{\"csv_path\":\"" + jsonPath(first) + "\"}", true, 100),
                new AgentResult.ToolCall("oracle_to_studio", Map.of("sql", "final aggregation"),
                        "{\"csv_path\":\"" + jsonPath(second) + "\"}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-1");

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).filename()).isEqualTo("second.csv");
        assertThat(Files.exists(first)).isFalse();  // superseded probe cleaned up, not left behind
        assertThat(Files.exists(second)).isFalse(); // winner read then deleted (ephemeral disk)
    }

    @Test
    void keepsOneArtifactPerDistinctSkill(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("export.csv");
        Path report = tmp.resolve("report.html");
        Files.writeString(csv, "data");
        Files.writeString(report, "<html></html>");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.find("oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));
        when(registry.find("file_write")).thenReturn(Optional.of(
                skillWith("file_write", new SkillDefinition.ProducesArtifact("path", "output"))));

        AgentService service = new AgentService(registry, mock(ScriptToolExecutor.class));

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("oracle_to_studio", Map.of(),
                        "{\"csv_path\":\"" + jsonPath(csv) + "\"}", true, 100),
                new AgentResult.ToolCall("file_write", Map.of(),
                        "{\"path\":\"" + jsonPath(report) + "\"}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-2");

        assertThat(artifacts).extracting(AgentRunResponse.ArtifactDto::filename)
                .containsExactlyInAnyOrder("export.csv", "report.html");
    }

    @Test
    void failedCallsAreIgnored(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("shouldnotappear.csv");
        Files.writeString(csv, "data");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.find("oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));

        AgentService service = new AgentService(registry, mock(ScriptToolExecutor.class));

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("oracle_to_studio", Map.of(),
                        "{\"csv_path\":\"" + jsonPath(csv) + "\"}", false, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-3");

        assertThat(artifacts).isEmpty();
        assertThat(Files.exists(csv)).isTrue(); // untouched — not this method's concern to clean up a failed call
    }

    private static SkillDefinition skillWith(String name, SkillDefinition.ProducesArtifact declaration) {
        return new SkillDefinition(name, "desc", "python", 60, Map.of(), "impl", "raw", declaration);
    }

    /** Escapes backslashes for embedding a Windows path literal inside a JSON string in tests. */
    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
