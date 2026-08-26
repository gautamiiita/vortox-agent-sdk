package com.vortox.sidecar.service;

import com.vortox.agent.AgentResult;
import com.vortox.sidecar.api.AgentRunResponse;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import com.vortox.sidecar.skill.SkillDefinition;
import com.vortox.sidecar.skill.SkillEnvStore;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Which files a run actually hands back, and which it refuses to.
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
 *
 * <p>Every case here runs against a <em>real</em> {@link ScriptToolExecutor} rooted at a temporary
 * workspace, rather than a mock. The path confinement is the point: artifact delivery reads paths a
 * skill reported, and a mocked executor would let the dedup assertions pass while saying nothing
 * about whether {@code /etc/passwd} is deliverable. {@link #refusesAPathOutsideTheRunWorkspace}
 * covers that directly.
 */
class AgentServiceExtractArtifactsTest {

    private static final SkillDefinition.ProducesArtifact OUTPUT_SOURCED =
            new SkillDefinition.ProducesArtifact("csv_path", "output");

    private ScriptToolExecutor executor;
    private Path workspaceRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        workspaceRoot = tmp.resolve("workspaces");
        executor = new ScriptToolExecutor(mock(SkillRegistry.class), mock(SkillEnvStore.class));
        ReflectionTestUtils.setField(executor, "workspaceRoot", workspaceRoot.toString());
        ReflectionTestUtils.setField(executor, "confineToRunWorkspace", true);
        ReflectionTestUtils.setField(executor, "retentionHours", 24L);
    }

    /** Creates this run's workspace and returns it, so test files land where a skill's would. */
    private Path runWorkspace(String runId) throws IOException {
        Path dir = workspaceRoot.resolve(runId);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void supersedesWhenTheSameSkillRewritesTheSamePath() throws IOException {
        Path out = runWorkspace("run-1").resolve("result.csv");
        Files.writeString(out, "final-answer");   // the retry overwrote its own output

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "oracle_to_studio"))
                .thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));

        AgentService service = new AgentService(registry, executor);

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
    void returnsEveryDistinctFileTheSameSkillWrote() throws IOException {
        Path run  = runWorkspace("run-html-csv");
        Path html = run.resolve("CUBE_contact_demographics_report.html");
        Path csv  = run.resolve("CUBE_contact_demographics.csv");
        Files.writeString(html, "<html>report</html>");
        Files.writeString(csv, "CONTACT_ID\n1\n");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "file_write"))
                .thenReturn(Optional.of(skillWith("file_write",
                        new SkillDefinition.ProducesArtifact("path", "output"))));

        AgentService service = new AgentService(registry, executor);

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
    void keepsOneArtifactPerDistinctSkill() throws IOException {
        Path run    = runWorkspace("run-2");
        Path csv    = run.resolve("export.csv");
        Path report = run.resolve("report.html");
        Files.writeString(csv, "data");
        Files.writeString(report, "<html></html>");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));
        when(registry.findFor(null, "file_write")).thenReturn(Optional.of(
                skillWith("file_write", new SkillDefinition.ProducesArtifact("path", "output"))));

        AgentService service = new AgentService(registry, executor);

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
    void failedCallsAreIgnored() throws IOException {
        Path csv = runWorkspace("run-3").resolve("shouldnotappear.csv");
        Files.writeString(csv, "data");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "oracle_to_studio")).thenReturn(Optional.of(skillWith("oracle_to_studio", OUTPUT_SOURCED)));

        AgentService service = new AgentService(registry, executor);

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("oracle_to_studio", Map.of(),
                        "{\"csv_path\":\"" + jsonPath(csv) + "\"}", false, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-3");

        assertThat(artifacts).isEmpty();
        assertThat(Files.exists(csv)).isTrue(); // untouched — not this method's concern to clean up a failed call
    }

    // ── Confinement ───────────────────────────────────────────────────────────

    /**
     * The reason this method takes an executor at all.
     *
     * <p>A skill declares where it wrote its deliverable, and until this was confined that path was
     * read verbatim: {@code /proc/self/environ} came back base64-encoded in the response, handing
     * the caller {@code ANTHROPIC_API_KEY} and {@code SIDECAR_API_KEY}. The path here is a plain
     * file outside the workspace, which is the same escape without depending on {@code /proc}
     * existing. It must not be delivered, and — since the delivery path also deletes what it
     * reads — it must still be on disk afterwards.
     */
    @Test
    void refusesAPathOutsideTheRunWorkspace() throws IOException {
        runWorkspace("run-escape");
        Path outside = workspaceRoot.getParent().resolve("someone-elses-secret.txt");
        Files.writeString(outside, "not yours");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "file_read"))
                .thenReturn(Optional.of(skillWith("file_read",
                        new SkillDefinition.ProducesArtifact("path", "output"))));

        AgentService service = new AgentService(registry, executor);

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("file_read", Map.of(),
                        "{\"path\":\"" + jsonPath(outside) + "\"}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-escape");

        assertThat(artifacts).as("an escaping path is not a deliverable").isEmpty();
        assertThat(Files.exists(outside)).as("and must not have been deleted either").isTrue();
        assertThat(Files.readString(outside)).isEqualTo("not yours");
    }

    /**
     * The input-sourced variant, which is the one that was actually reachable by the model:
     * {@code confineParams} leaves the caller's map untouched, so the recorded tool input is the raw
     * path the model asked for — traversal and all — not the rewritten one.
     */
    @Test
    void refusesAnEscapingPathTakenFromTheRecordedToolInput() throws IOException {
        runWorkspace("run-traversal");
        Path outside = workspaceRoot.getParent().resolve("target.txt");
        Files.writeString(outside, "sensitive");

        SkillRegistry registry = mock(SkillRegistry.class);
        when(registry.findFor(null, "file_read"))
                .thenReturn(Optional.of(skillWith("file_read",
                        new SkillDefinition.ProducesArtifact("report_path", "input"))));

        AgentService service = new AgentService(registry, executor);

        List<AgentResult.ToolCall> calls = List.of(
                new AgentResult.ToolCall("file_read",
                        Map.of("report_path", "../target.txt"),
                        "{\"ok\":true}", true, 100)
        );

        List<AgentRunResponse.ArtifactDto> artifacts = service.extractArtifacts(calls, "run-traversal");

        assertThat(artifacts).isEmpty();
        assertThat(Files.exists(outside)).isTrue();
    }

    private static SkillDefinition skillWith(String name, SkillDefinition.ProducesArtifact declaration) {
        return new SkillDefinition(name, "desc", "python", 60, Map.of(), "impl", "raw", declaration);
    }

    /** Escapes backslashes for embedding a Windows path literal inside a JSON string in tests. */
    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
