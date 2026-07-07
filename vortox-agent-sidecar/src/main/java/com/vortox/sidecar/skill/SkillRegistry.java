package com.vortox.sidecar.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Scans the skills directory on startup and on demand.
 * Each skill lives in {skillsPath}/{skill-name}/SKILL.md — a plain YAML file.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    @Value("${sidecar.skills-path:/app/skills}")
    private String skillsPath;

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    @PostConstruct
    public void load() {
        skills.clear();
        Path base = Path.of(skillsPath);
        if (!Files.isDirectory(base)) {
            log.warn("Skills directory not found: {}", base);
            return;
        }

        try (Stream<Path> entries = Files.list(base)) {
            entries.filter(Files::isDirectory).forEach(this::loadSkill);
        } catch (IOException e) {
            log.error("Failed to scan skills directory: {}", e.getMessage());
        }
    }

    private void loadSkill(Path skillDir) {
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillFile)) return;

        try {
            String content = Files.readString(skillFile);
            ParsedSkill p = parse(content);

            if (p.name() == null || p.implementation() == null) {
                log.warn("Skipping {}: missing 'name' or 'implementation'", skillFile);
                return;
            }

            String dirName = skillDir.getFileName().toString();
            if (!dirName.equals(p.name())) {
                log.warn("Skill directory '{}' contains a SKILL.md whose name is '{}' — loading under '{}'. " +
                        "Rename the directory or fix the 'name' field to avoid this drift.",
                        dirName, p.name(), p.name());
            }

            skills.put(p.name(), new SkillDefinition(
                    p.name(), p.description(), p.language(), p.timeoutSeconds(), p.inputSchema(), p.implementation(), content));
            log.debug("Loaded skill: {}", p.name());
        } catch (Exception e) {
            log.error("Failed to load skill from {}: {}", skillFile, e.getMessage());
        }
    }

    /** Parsed-but-not-yet-registered view of a SKILL.md — lets save() validate before writing anything. */
    private record ParsedSkill(String name, String description, String language, int timeoutSeconds,
                                Map<String, Object> inputSchema, String implementation) {}

    @SuppressWarnings("unchecked")
    private ParsedSkill parse(String content) {
        Yaml yaml = new Yaml();
        Map<String, Object> parsed = yaml.load(content);
        if (parsed == null) parsed = Map.of();

        String name           = (String) parsed.get("name");
        String description    = (String) parsed.getOrDefault("description", "");
        String language       = (String) parsed.getOrDefault("language", "bash");
        int    timeout        = ((Number) parsed.getOrDefault("timeout_seconds", 60)).intValue();
        Map<String, Object> schema = (Map<String, Object>) parsed.get("input_schema");
        String implementation = (String) parsed.get("implementation");

        return new ParsedSkill(name, description, language, timeout, schema, implementation);
    }

    public Optional<SkillDefinition> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Collection<SkillDefinition> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    public List<SkillDefinition> subset(List<String> names) {
        if (names == null || names.isEmpty()) return new ArrayList<>(skills.values());
        return names.stream().map(skills::get).filter(Objects::nonNull).toList();
    }

    public int count() {
        return skills.size();
    }

    /**
     * Save a skill from raw SKILL.md content. Creates or replaces {skillsPath}/{name}/SKILL.md
     * and reloads the registry entry. Used by the upload endpoint.
     * <p>
     * Validates the content — and that its {@code name:} field matches {@code name} — before
     * writing anything to disk. Without this check a mismatch would still get written under
     * {@code name}'s directory while the registry entry ends up keyed by the content's own
     * {@code name:} field, silently orphaning the directory and confusing later edits/deletes.
     */
    public SkillDefinition save(String name, String content) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");

        ParsedSkill p = parse(content);
        if (p.name() == null || p.implementation() == null) {
            throw new IllegalArgumentException("SKILL.md must define both 'name' and 'implementation'");
        }
        if (!p.name().equals(name)) {
            throw new IllegalArgumentException(
                    "Name mismatch: requested to save as '" + name + "' but the SKILL.md content declares name '"
                            + p.name() + "'. They must match.");
        }

        Path skillDir = Path.of(skillsPath, name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);

        SkillDefinition saved = new SkillDefinition(
                p.name(), p.description(), p.language(), p.timeoutSeconds(), p.inputSchema(), p.implementation(), content);
        skills.put(p.name(), saved);
        log.info("Saved skill: {}", name);
        return saved;
    }

    /**
     * Delete a skill by name. Removes its directory and evicts it from the registry.
     * Returns false if the skill was not found.
     */
    public boolean delete(String name) throws IOException {
        Path skillDir = Path.of(skillsPath, name);
        if (!Files.exists(skillDir)) return false;

        try (Stream<Path> walker = Files.walk(skillDir)) {
            walker.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
        skills.remove(name);
        log.info("Deleted skill: {}", name);
        return true;
    }
}
