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
 * Which files a run actually hands back.
 *
 * <p>Deduplication is by (skill, path). A repeat write to the same path supersedes the earlier one,
 * so a skill retrying and overwriting its own output still yields a single download. Writes to
 * different paths are different deliverables and all of them are returned.
 *
 * <p>That second half is a correction. Keying on the skill alone meant a general-purpose writer could
 * only ever return one file per run: an agent that wrote an HTML report and then regenerated the
 * companion CSV delivered the CSV and deleted the report, having already told the user about both.
 * The cost is that a skill naming every export uniquely now surfaces each attempt — the better
 * failure, since a reader offered two files can choose and a reader offered none cannot.
 */
class AgentServiceExtractArtifactsTest {

    private static final SkillDefinition.ProducesArtifact OUTPUT_SOURCED =
            new SkillDefinition.ProducesArtifact("csv_path", "output");

    @Test
    void supersedesWhenTheSameSkillRewritesTheSamePath(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("result.csv");
        Files.writeString(out, "final-answer");   // the retry overwrote its own output

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "oracle_to_studio"))
                .thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));

        AgentService service = new AgentService(registry, mock(ScriptToolExecutor.class));

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("oracle_to_studio", Map.of("sql", "explore columns"),
                        "{\"csv_path\":\"" + jsonPath(out) + "\"}", true, 100),
                new AgentResult.ToolCall("oracle_to_studio", Map.of("sql", "final aggregation"),
                        "{\"csv_path\":\"" + jsonPath(out) + "\"}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-1");

        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.get(0).filename()).isEqualTo("result.csv");
        assertThat(Files.exists(out)).isFalse();  // read then deleted (the disk is ephemeral)
    }

    /**
     * The reported bug: a report and its data file, written by the same skill, both promised to the
     * user in the reply. Before this, only the last survived and the other was deleted.
     */
    @Test
    void returnsEveryDistinctFileTheSameSkillWrote(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path html = tmp.resolve("CUBE_contact_demographics_report.html");
        Path csv  = tmp.resolve("CUBE_contact_demographics.csv");
        Files.writeString(html, "<html>report</html>");
        Files.writeString(csv, "CONTACT_ID\n1\n");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "file_write"))
                .thenReturn(Optional.of(skillWith("file_write",
                        new SkillDefinition.ProducesArtifact("path", "output"))));

        AgentService service = new AgentService(registry, mock(ScriptToolExecutor.class));

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("file_write", Map.of(),
                        "{\"path\":\"" + jsonPath(html) + "\"}", true, 100),
                new AgentResult.ToolCall("file_write", Map.of(),
                        "{\"path\":\"" + jsonPath(csv) + "\"}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-html-csv");

        assertThat(artifacts).extracting(AgentRunResponse.ArtifactDto::filename)
                .containsExactly("CUBE_contact_demographics_report.html",
                                 "CUBE_contact_demographics.csv");
        assertThat(Files.exists(html)).as("delivered, not discarded").isFalse();
        assertThat(Files.exists(csv)).isFalse();
    }

    @Test
    void keepsOneArtifactPerDistinctSkill(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path csv = tmp.resolve("export.csv");
        Path report = tmp.resolve("report.html");
        Files.writeString(csv, "data");
        Files.writeString(report, "<html></html>");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));
        when(registry.findFor(null, "file_write")).thenReturn(Optional.of(
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
        when(registry.findFor(null, "oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));

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
