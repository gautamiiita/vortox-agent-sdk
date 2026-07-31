package com.vortox.sidecar.skill;

import com.vortox.agent.spi.ToolExecutor.ToolExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Covers the sidecar's per-run workspace confinement. The threat this closes is concrete: the
 * synced {@code file_read} skill is a bare {@code open(params['path'])} and {@code file_write}
 * only defaults <em>relative</em> paths into the workspace, so before confinement an absolute
 * path reached anything in the container — {@code /proc/1/environ} (the Anthropic and Vortox API
 * keys), the bind-mounted {@code /app/skills} (rewriting a SKILL.md is code execution on the next
 * call), and every other run's files under the shared workspace root.
 * <p>
 * Enforcement deliberately lives in the executor rather than in the skill bodies, because the
 * same SKILL.md content is also run by the Vortox backend, where sharing the global workspace
 * root across agents and sessions is intended behaviour.
 */
class ScriptToolExecutorWorkspaceTest {

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

    @Test
    void givesEachRunItsOwnWorkspaceDirectory() throws IOException {
        Path runA = executor.prepareRunWorkspace("11111111-aaaa-bbbb-cccc-dddddddddddd");
        Path runB = executor.prepareRunWorkspace("22222222-aaaa-bbbb-cccc-dddddddddddd");

        assertThat(runA).isNotEqualTo(runB);
        assertThat(runA).isDirectory();
        assertThat(runB).isDirectory();
        assertThat(runA.getParent()).isEqualTo(runB.getParent());
    }

    @Test
    void resolvesRelativePathsIntoTheRunsOwnWorkspace() throws IOException {
        Path run = executor.prepareRunWorkspace("run-relative");

        Map<String, Object> confined = executor.confineParams(
                Map.of("path", "report.csv", "content", "a,b,c"), run, "file_write");

        assertThat(confined.get("path")).isEqualTo(run.resolve("report.csv").toString());
        assertThat(confined.get("content")).isEqualTo("a,b,c");  // non-path params untouched
    }

    @Test
    void rejectsTheContainerEnvironmentExfiltrationPath() throws IOException {
        Path run = executor.prepareRunWorkspace("run-proc");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", "/proc/1/environ"), run, "file_read"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside this run's workspace");
    }

    @Test
    void rejectsWritesToTheBindMountedSkillsDirectory() throws IOException {
        Path run = executor.prepareRunWorkspace("run-skills");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", "/app/skills/file_read/SKILL.md"), run, "file_write"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void rejectsTraversalOutOfTheRunWorkspace() throws IOException {
        Path run = executor.prepareRunWorkspace("run-traversal");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", "../other-run/stolen.txt"), run, "file_read"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void rejectsAnotherRunsWorkspaceEvenByAbsolutePath() throws IOException {
        Path mine = executor.prepareRunWorkspace("run-mine");
        Path theirs = executor.prepareRunWorkspace("run-theirs");
        Files.writeString(theirs.resolve("secret.txt"), "not yours");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", theirs.resolve("secret.txt").toString()), mine, "file_read"))
                .isInstanceOf(ToolExecutionException.class);
    }

    @Test
    void rejectsASymlinkPointingOutOfTheRunWorkspace() throws IOException {
        Path run = executor.prepareRunWorkspace("run-symlink");
        Path outside = workspaceRoot.getParent().resolve("outside.txt");
        Files.writeString(outside, "escaped");

        Path link = run.resolve("shortcut.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;  // Windows without developer mode / privileges — nothing to assert
        }

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", link.toString()), run, "file_read"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void confinesProjectRootSoBuildSkillsCannotReachTheHost() throws IOException {
        Path run = executor.prepareRunWorkspace("run-build");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("projectRoot", "/app"), run, "docker_build_and_run"))
                .isInstanceOf(ToolExecutionException.class);

        Map<String, Object> ok = executor.confineParams(
                Map.of("projectRoot", "my-app", "projectName", "my-app"), run, "docker_build_and_run");
        assertThat(ok.get("projectRoot")).isEqualTo(run.resolve("my-app").toString());
        assertThat(ok.get("projectName")).isEqualTo("my-app");  // not a path param despite the value
    }

    @Test
    void classifiesParamNamesByTheirLastToken() {
        assertThat(ScriptToolExecutor.looksLikePathParam("path")).isTrue();
        assertThat(ScriptToolExecutor.looksLikePathParam("projectRoot")).isTrue();
        assertThat(ScriptToolExecutor.looksLikePathParam("compose_file")).isTrue();
        assertThat(ScriptToolExecutor.looksLikePathParam("outputDirectory")).isTrue();

        // Values that merely end in a path-ish substring must not be rewritten.
        assertThat(ScriptToolExecutor.looksLikePathParam("profile")).isFalse();
        assertThat(ScriptToolExecutor.looksLikePathParam("projectName")).isFalse();
        assertThat(ScriptToolExecutor.looksLikePathParam("sql")).isFalse();
        assertThat(ScriptToolExecutor.looksLikePathParam("max_rows")).isFalse();
    }

    @Test
    void leavesTheCallersParamMapUntouchedSoArtifactExtractionStillSeesWhatTheAgentAsked()
            throws IOException {
        Path run = executor.prepareRunWorkspace("run-immutable");
        Map<String, Object> original = new LinkedHashMap<>(Map.of("path", "out.csv"));

        Map<String, Object> confined = executor.confineParams(original, run, "file_write");

        assertThat(original.get("path")).isEqualTo("out.csv");
        assertThat(confined.get("path")).isNotEqualTo("out.csv");
    }

    @Test
    void passesEverythingThroughWhenConfinementIsDisabled() throws IOException {
        ReflectionTestUtils.setField(executor, "confineToRunWorkspace", false);
        Path run = executor.prepareRunWorkspace("run-off");

        assertThatCode(() -> executor.confineParams(
                Map.of("path", "/proc/1/environ"), run, "file_read")).doesNotThrowAnyException();
    }

    @Test
    void nestsRunWorkspacesUnderTheirTenant() throws IOException {
        Path cube = executor.prepareRunWorkspace("tenant-cube", "run-1");
        Path arena = executor.prepareRunWorkspace("tenant-arena", "run-1");

        // Same runId, different tenants — the tenant segment is what keeps them apart.
        assertThat(cube).isNotEqualTo(arena);
        assertThat(cube.getParent().getFileName()).hasToString("tenant-cube");
        assertThat(arena.getParent().getFileName()).hasToString("tenant-arena");
    }

    @Test
    void oneTenantsRunCannotReachAnotherTenantsWorkspace() throws IOException {
        Path cube = executor.prepareRunWorkspace("tenant-cube", "run-cube");
        Path arena = executor.prepareRunWorkspace("tenant-arena", "run-arena");
        Files.writeString(arena.resolve("secret.txt"), "not yours");

        assertThatThrownBy(() -> executor.confineParams(
                Map.of("path", arena.resolve("secret.txt").toString()), cube, "file_read"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside this run's workspace");
    }

    @Test
    void aTenantCodeWithSeparatorsCannotEscapeTheWorkspaceRoot() throws IOException {
        // The tenant code arrives from a request header, so it must not be able to redirect the
        // workspace outside the root.
        Path escaped = executor.prepareRunWorkspace("../../etc", "run-1");

        assertThat(escaped.startsWith(workspaceRoot.toRealPath())).isTrue();
    }

    @Test
    void reapsOnlyExpiredRunWorkspacesAndNeverForeignDirectories() throws IOException {
        Path stale = executor.prepareRunWorkspace("aaaaaaaa-1111-2222-3333-444444444444");
        Path fresh = executor.prepareRunWorkspace("bbbbbbbb-1111-2222-3333-444444444444");
        Path tenantStale = executor.prepareRunWorkspace(
                "tenant-cube", "cccccccc-1111-2222-3333-444444444444");
        Path foreign = workspaceRoot.resolve("tnam-poc-p12");   // a Vortox-internal project dir
        Files.createDirectories(foreign);
        Path looseFile = workspaceRoot.resolve("promotional_email_cube.html");
        Files.writeString(looseFile, "<html></html>");

        java.nio.file.attribute.FileTime expired = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofHours(48)));
        Files.setLastModifiedTime(stale, expired);
        Files.setLastModifiedTime(tenantStale, expired);
        Files.setLastModifiedTime(foreign, expired);

        executor.evictStaleRunWorkspaces();

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        // Nested one level deeper under its tenant, and still reaped.
        assertThat(tenantStale).doesNotExist();
        assertThat(tenantStale.getParent()).exists();   // the tenant directory itself survives
        assertThat(foreign).exists();     // not runId-shaped — left for whoever owns it
        assertThat(looseFile).exists();
    }
}
