package com.vortox.sidecar.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.spi.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implements ToolExecutor by writing the skill's implementation to a temp file
 * and executing it with the appropriate interpreter.
 * Params are passed as a JSON file — the script reads them via sys.argv[1] (Python)
 * or $1 (bash) or process.argv[2] (Node).
 */
@Component
public class ScriptToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScriptToolExecutor.class);

    private final SkillRegistry registry;
    private final ObjectMapper objectMapper;

    public ScriptToolExecutor(SkillRegistry registry) {
        this.registry = registry;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String execute(String toolName, Map<String, Object> params, String runId) {
        SkillDefinition skill = registry.find(toolName)
                .orElseThrow(() -> new ToolExecutionException("Unknown skill: " + toolName));

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("vortox-skill-" + toolName + "-");

            // Write the script implementation to a temp file
            String ext = extensionFor(skill.language());
            Path scriptFile = workDir.resolve("skill" + ext);
            Files.writeString(scriptFile, skill.implementation());
            scriptFile.toFile().setExecutable(true);

            // Write params as JSON to a temp file (scripts read via argv[1])
            Path paramsFile = workDir.resolve("params.json");
            Files.writeString(paramsFile, objectMapper.writeValueAsString(params));

            // Build and run the process
            String[] cmd = commandFor(skill.language(), scriptFile.toString(), paramsFile.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workDir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(
                            Path.of(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null").toFile()));

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

    private static String[] commandFor(String language, String scriptPath, String paramsPath) {
        return switch (language.toLowerCase()) {
            case "python", "python3" -> new String[]{"python3", scriptPath, paramsPath};
            case "node", "nodejs"   -> new String[]{"node", scriptPath, paramsPath};
            default                  -> new String[]{"/bin/bash", scriptPath, paramsPath};
        };
    }

    private static String extensionFor(String language) {
        return switch (language.toLowerCase()) {
            case "python", "python3" -> ".py";
            case "node", "nodejs"   -> ".js";
            default                  -> ".sh";
        };
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
