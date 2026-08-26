package com.vortox.sidecar.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.spi.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Implements ToolExecutor by writing the skill's implementation to a temp file
 * and executing it with the appropriate interpreter.
 * Params are passed as a JSON file — the script reads them via sys.argv[1] (Python)
 * or $1 (bash) or process.argv[2] (Node).
 * <p>
 * <strong>Workspace confinement.</strong> Each run gets its own directory under the workspace
 * root ({@code <root>/<runId>}), handed to the skill process as {@code WORKSPACE_PATH}, and
 * every path-valued parameter is resolved and verified to land inside it. Two reasons this lives
 * here rather than in the skill scripts: the same SKILL.md bodies are also executed by the Vortox
 * backend ({@code SkillExecutorService}/{@code SkillContainerExecutor}), where agents and
 * sessions are meant to share the global {@code /app/workspaces} root — so the restriction has to
 * be a property of <em>this</em> executor, not of the skill. And a skill's own path handling
 * can't be trusted to enforce anything: {@code file_read} is a bare {@code open(params['path'])},
 * which without this check reaches any absolute path in the container (including
 * {@code /proc/1/environ}, i.e. the API keys) and any file on the host bind-mounts.
 */
@Component
public class ScriptToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptToolExecutor.class);

    /**
     * Trailing parameter-name tokens that mark a value as a filesystem path. Matched against the
     * last token only (camelCase and snake_case both split), so {@code projectRoot} and
     * {@code compose_file} are confined while {@code projectName} and {@code profile} are not.
     */
    private static final Set<String> PATH_PARAM_TOKENS = Set.of(
            "path", "paths", "dir", "dirs", "directory", "directories",
            "root", "file", "files", "filename", "filenames");

    private final SkillRegistry registry;
    private final SkillEnvStore skillEnvStore;
    private final ObjectMapper objectMapper;

    @Value("${WORKSPACE_PATH:/app/workspaces}")
    private String workspaceRoot;

    /** Escape hatch: set false to restore the pre-confinement behaviour if a skill needs a path outside its run. */
    @Value("${sidecar.workspace.confine:true}")
    private boolean confineToRunWorkspace;

    /** Per-run directories are the agent's scratch space, not durable storage — reaped after this. */
    @Value("${sidecar.workspace.retention-hours:24}")
    private long retentionHours;

    public ScriptToolExecutor(SkillRegistry registry, SkillEnvStore skillEnvStore) {
        this.registry = registry;
        this.skillEnvStore = skillEnvStore;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * A {@link ToolExecutor} that can only ever act for {@code tenantId}.
     *
     * <p>{@link ToolExecutor#execute} has no tenant parameter, so once a run is handed one of these
     * there is no way for it — or for {@code ReactLoop} — to name a different tenant. That is the
     * point: the tenant is fixed once, at the request boundary, and travels as a captured value
     * rather than as ambient state. A {@code ThreadLocal} would leak here, because
     * {@code ChatRunService} hands runs to a worker pool.
     */
    public ToolExecutor forTenant(String tenantId) {
        return (toolName, params, runId) -> executeFor(tenantId, toolName, params, runId);
    }

    /** Shared-tier execution, for single-tenant deployments and callers with no tenant. */
    @Override
    public String execute(String toolName, Map<String, Object> params, String runId) {
        return executeFor(null, toolName, params, runId);
    }

    private String executeFor(String tenantId, String toolName, Map<String, Object> params, String runId) {
        SkillDefinition skill = registry.findFor(tenantId, toolName)
                .orElseThrow(() -> new ToolExecutionException("Unknown skill: " + toolName));

        Path workDir = null;
        try {
            Path runWorkspace = prepareRunWorkspace(tenantId, runId);
            Map<String, Object> effectiveParams = confineParams(params, runWorkspace, toolName);

            workDir = Files.createTempDirectory("vortox-skill-" + toolName + "-");

            // Write the script implementation to a temp file
            String ext = extensionFor(skill.language());
            Path scriptFile = workDir.resolve("skill" + ext);
            Files.writeString(scriptFile, skill.implementation());
            scriptFile.toFile().setExecutable(true);

            // Write params as JSON to a temp file (scripts read via argv[1])
            Path paramsFile = workDir.resolve("params.json");
            Files.writeString(paramsFile, objectMapper.writeValueAsString(effectiveParams));

            // Build and run the process
            String[] cmd = commandFor(skill.language(), scriptFile.toString(), paramsFile.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workDir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(
                            Path.of(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null").toFile()));

            // Point the skill at this run's own workspace rather than the shared root the
            // container inherits, so relative paths (file_write's default) can't collide with
            // or overwrite another run's files.
            pb.environment().put("WORKSPACE_PATH", runWorkspace.toString());

            // Inject skill-specific env vars resolved from Vortox secrets (in-memory, not on disk).
            // Scoped to this run's tenant: the same shared skill body runs against a different
            // database per institution, so borrowing another tenant's values would succeed against
            // the wrong system rather than fail.
            Map<String, String> envVars = skillEnvStore.get(tenantId, toolName);
            if (!envVars.isEmpty()) {
                pb.environment().putAll(envVars);
                log.debug("Injected {} env var(s) for skill '{}' (tenant={})",
                        envVars.size(), toolName, tenantId);
            }

            Process process = pb.start();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread t1 = new Thread(() -> drain(process.getInputStream(), stdout));
            Thread t2 = new Thread(() -> drain(process.getErrorStream(), stderr));
            t1.start(); t2.start();

            boolean done = process.waitFor(skill.timeoutSeconds(), TimeUnit.SECONDS);
            t1.join(2000); t2.join(2000);

            if (!done) {
                process.destroyForcibly();
                throw new ToolExecutionException("Skill timed out after " + skill.timeoutSeconds() + "s");
            }

            int exitCode = process.exitValue();
            String out = stdout.toString().trim();
            String err = stderr.toString().trim();

            if (exitCode != 0) {
                log.warn("Skill {} exited {} | stderr: {}", toolName, exitCode, err);
                String errorDetail = !err.isEmpty() ? err : "exit code " + exitCode;
                throw new ToolExecutionException("Skill failed: " + errorDetail);
            }

            return out.isEmpty() ? "{\"success\":true}" : out;

        } catch (ToolExecutionException tee) {
            throw tee;
        } catch (Exception e) {
            log.error("Skill {} execution error: {}", toolName, e.getMessage());
            throw new ToolExecutionException("Skill execution failed: " + e.getMessage(), e);
        } finally {
            if (workDir != null) deleteQuietly(workDir);
        }
    }

    // ── Workspace confinement ─────────────────────────────────────────────────

    /**
     * Creates (idempotently) this run's own directory under the workspace root and returns its
     * real path — symlinks resolved, so the {@code startsWith} checks in
     * {@link #confinePath} can't be defeated by a link planted inside the workspace.
     */
    /* package-private for testability */
    Path prepareRunWorkspace(String runId) throws java.io.IOException {
        return prepareRunWorkspace(null, runId);
    }

    /**
     * Nests a run's workspace under its tenant ({@code <root>/<tenant>/<runId>}) so confinement
     * separates tenants as well as runs — one institution's files are then unreachable from
     * another's run even by absolute path. Falls back to {@code <root>/<runId>} when there is no
     * tenant, which is what a single-tenant deployment gets.
     */
    /* package-private for testability */
    Path prepareRunWorkspace(String tenantId, String runId) throws java.io.IOException {
        Path root = Path.of(workspaceRoot);
        if (tenantId != null && !tenantId.isBlank()) {
            root = root.resolve(sanitizeRunId(tenantId));
        }
        Files.createDirectories(root);
        Path runWorkspace = root.resolve(sanitizeRunId(runId));
        Files.createDirectories(runWorkspace);
        return runWorkspace.toRealPath();
    }

    /**
     * Returns a copy of {@code params} with every path-valued entry rewritten to an absolute path
     * inside {@code runWorkspace}, rejecting any that escapes it. The caller's map is left
     * untouched: {@code AgentService.extractArtifacts} reads recorded tool-call inputs, and the
     * agent's own view of what it asked for shouldn't silently differ from what it passed.
     */
    /* package-private for testability */
    Map<String, Object> confineParams(Map<String, Object> params, Path runWorkspace, String toolName) {
        if (!confineToRunWorkspace || params == null || params.isEmpty()) return params;

        Map<String, Object> confined = new LinkedHashMap<>(params);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!(e.getValue() instanceof String raw) || raw.isBlank()) continue;
            if (!looksLikePathParam(e.getKey())) continue;

            String resolved = confinePath(raw, runWorkspace);
            confined.put(e.getKey(), resolved);
            if (!resolved.equals(raw)) {
                log.debug("Skill '{}': confined param {}='{}' to '{}'", toolName, e.getKey(), raw, resolved);
            }
        }
        return confined;
    }

    /**
     * Resolves {@code raw} against {@code runWorkspace} (relative) or takes it as given
     * (absolute), then rejects anything outside the run workspace. Both the lexically normalised
     * path and the real path of its deepest existing ancestor are checked, so neither
     * {@code ../} traversal nor a symlink pointing out of the workspace gets through.
     */
    /* package-private for testability */
    static String confinePath(String raw, Path runWorkspace) {
        Path normalized = (Path.of(raw).isAbsolute() ? Path.of(raw) : runWorkspace.resolve(raw)).normalize();
        if (!normalized.startsWith(runWorkspace)) {
            throw new ToolExecutionException("Path '" + raw + "' is outside this run's workspace ("
                    + runWorkspace + "). Skills may only read and write inside their own workspace.");
        }
        // The lexical check above can still be satisfied by a path whose existing prefix is a
        // symlink out of the workspace, so verify where that prefix actually points.
        try {
            Path existing = normalized;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null && !existing.toRealPath().startsWith(runWorkspace)) {
                throw new ToolExecutionException("Path '" + raw + "' resolves outside this run's workspace ("
                        + runWorkspace + ") via a symbolic link.");
            }
        } catch (java.io.IOException io) {
            throw new ToolExecutionException("Could not verify path '" + raw + "': " + io.getMessage(), io);
        }
        return normalized.toString();
    }

    /**
     * Resolves a path a skill reported as a deliverable, confined to that run's own workspace.
     *
     * <p>Exists because artifact delivery reads paths this class never got to confine. A skill
     * declaring {@code produces_artifact} names its output file either in its JSON output or in its
     * recorded tool-call <em>input</em> — and {@link #confineParams} deliberately leaves the
     * caller's map untouched, so the recorded input is the raw value the model supplied, not the
     * rewritten one. Reading that value directly, which is what {@code AgentService} used to do,
     * turned artifact delivery into an arbitrary file read: {@code /proc/self/environ} came back
     * base64-encoded in the response, carrying this container's API keys, and the over-cap cleanup
     * branch deleted whatever path it was handed.
     *
     * <p>Returns empty rather than throwing when the path escapes or cannot be resolved. Artifact
     * extraction runs after the model's work is done and reports per-file failures without failing
     * the run, so a refusal here should read as "no artifact" and be logged, not abort a completed
     * answer.
     *
     * @param tenantId the run's tenant, or null for the shared tier
     * @param runId    the run whose workspace bounds the path
     * @param rawPath  the path as the skill reported it
     */
    public java.util.Optional<Path> resolveDeliverablePath(String tenantId, String runId, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return java.util.Optional.empty();
        if (!confineToRunWorkspace) return java.util.Optional.of(Path.of(rawPath));
        try {
            Path runWorkspace = prepareRunWorkspace(tenantId, runId);
            return java.util.Optional.of(Path.of(confinePath(rawPath, runWorkspace)));
        } catch (ToolExecutionException | java.io.IOException e) {
            log.warn("Refusing artifact path '{}' for run {} (tenant={}): {}",
                    rawPath, runId, tenantId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** True when the parameter's last name token marks it as a filesystem path. */
    /* package-private for testability */
    static boolean looksLikePathParam(String name) {
        if (name == null || name.isBlank()) return false;
        // Split on separators and camelCase boundaries: projectRoot → [project, Root].
        String[] tokens = name.split("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
        if (tokens.length == 0) return false;
        return PATH_PARAM_TOKENS.contains(tokens[tokens.length - 1].toLowerCase(Locale.ROOT));
    }

    /** Keeps a runId usable as a single directory name — never a traversal or a nested path. */
    private static String sanitizeRunId(String runId) {
        String safe = runId == null ? "" : runId.replaceAll("[^A-Za-z0-9._-]", "");
        while (safe.startsWith(".")) safe = safe.substring(1);
        return safe.isBlank() ? "unknown-run" : safe;
    }

    /**
     * Reaps run workspaces past their retention window. Only directories whose name matches a
     * runId shape are considered — anything else under the root was put there by something other
     * than this executor (the workspace root can be a shared mount) and is left alone.
     */
    @Scheduled(fixedDelayString = "${sidecar.workspace.cleanup-interval-ms:3600000}")
    public void evictStaleRunWorkspaces() {
        Path root = Path.of(workspaceRoot);
        if (!Files.isDirectory(root)) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(retentionHours));
        int removed = reapRunDirs(root, cutoff);

        // Run workspaces sit one level deeper once nested under a tenant, so each non-runId
        // directory is also searched. Only runId-shaped leaves are ever deleted, which is what
        // keeps a tenant directory (or anything else sharing the root) safe.
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child) && !isRunIdShaped(child)) {
                    removed += reapRunDirs(child, cutoff);
                }
            }
        } catch (Exception e) {
            log.warn("Run-workspace cleanup could not scan tenant directories: {}", e.getMessage());
        }

        if (removed > 0) {
            log.info("Run-workspace cleanup: removed {} workspace(s) older than {}h", removed, retentionHours);
        }
    }

    /** Deletes expired runId-shaped directories directly under {@code parent}; returns how many. */
    private int reapRunDirs(Path parent, Instant cutoff) {
        int removed = 0;
        try (var children = Files.list(parent)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child) || !isRunIdShaped(child)) continue;
                try {
                    if (Files.getLastModifiedTime(child).toInstant().isBefore(cutoff)) {
                        deleteQuietly(child);
                        removed++;
                    }
                } catch (Exception e) {
                    log.debug("Could not evaluate run workspace {}: {}", child, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Run-workspace cleanup failed under {}: {}", parent, e.getMessage());
        }
        return removed;
    }

    private static boolean isRunIdShaped(Path dir) {
        return dir.getFileName().toString().matches("[A-Za-z0-9]{8}-[A-Za-z0-9-]{20,}");
    }

    /**
     * The closed set of languages a skill may declare.
     *
     * <p>Both switches below used to end in a bash default, which meant a SKILL.md declaring
     * {@code language: ruby} was written to {@code skill.sh} and handed to {@code /bin/bash} — so
     * the error the author saw was a shell syntax complaint about their Ruby, and a body that
     * happened to parse in both languages ran the wrong way with no error at all. Naming the
     * supported set once, and refusing anything outside it, says what actually went wrong.
     */
    private static final Map<String, String> INTERPRETERS = Map.of(
            "bash",    "/bin/bash",
            "sh",      "/bin/bash",
            "python",  "python3",
            "python3", "python3",
            "node",    "node",
            "nodejs",  "node");

    private static final Map<String, String> EXTENSIONS = Map.of(
            "bash",    ".sh",
            "sh",      ".sh",
            "python",  ".py",
            "python3", ".py",
            "node",    ".js",
            "nodejs",  ".js");

    /** Normalised language key, or a refusal naming what is supported. */
    private static String languageKey(String language) {
        String key = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (!INTERPRETERS.containsKey(key)) {
            throw new ToolExecutionException("Skill declares an unsupported language '" + language
                    + "'. Supported: " + new java.util.TreeSet<>(INTERPRETERS.keySet()) + ".");
        }
        return key;
    }

    private static String[] commandFor(String language, String scriptPath, String paramsPath) {
        return new String[]{INTERPRETERS.get(languageKey(language)), scriptPath, paramsPath};
    }

    private static String extensionFor(String language) {
        return EXTENSIONS.get(languageKey(language));
    }

    private static void drain(java.io.InputStream in, StringBuilder out) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
        } catch (Exception ignored) {}
    }

    private static void deleteQuietly(Path dir) {
        try {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        } catch (Exception ignored) {}
    }
}
