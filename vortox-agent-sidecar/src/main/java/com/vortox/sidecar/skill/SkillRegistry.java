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
 * Scans the skills directory on startup and on demand, in two tiers.
 *
 * <p><strong>Shared</strong> skills live at {@code {skillsPath}/{skill-name}/SKILL.md} and are
 * visible to every tenant — the product toolkit. <strong>Tenant-private</strong> skills live at
 * {@code {skillsPath}/tenants/{tenantId}/{skill-name}/SKILL.md} and are only ever visible to that
 * tenant. This mirrors what Vortox already models: {@code Skill} is filtered with
 * {@code tenant_id = :tenantId OR tenant_id IS NULL}, so a null tenant is a shared skill and a
 * non-null one is private to its tenant.
 *
 * <p>Shared skills deliberately keep the original flat layout rather than moving under a
 * {@code shared/} directory: the skills directory is a host bind-mount in real deployments, and
 * relocating existing skill directories would strand them. {@code tenants} is therefore a reserved
 * name at the top level.
 *
 * <p>A tenant may define a skill whose name matches a shared one. That is allowed — customising a
 * standard skill is legitimate — and <strong>the tenant's copy wins</strong>. It is logged, because
 * silently running a different body than the shared one is otherwise very hard to diagnose. What is
 * never possible is one tenant reaching another's skill: lookups consult exactly one tenant's map
 * plus the shared map, with no global fallback.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** Reserved top-level directory name; everything under it is tenant-private. */
    static final String TENANTS_DIR = "tenants";

    /**
     * Marker written beside a SKILL.md that a Vortox sync delivered.
     *
     * <p>Provenance decides whether a skill may be offered as a tool at all under a control plane
     * ({@link #isUsable}), so holding it only in memory made the tool surface depend on whether a
     * sync had succeeded in <em>this process</em>. A restart, or a sync that failed — a pooled key
     * refused for want of a tenant code, a network blip — left every skill loaded from disk,
     * counted in the logs, and silently withheld from the agent. From the user's side that looks
     * like the assistant randomly losing its abilities mid-conversation.
     *
     * <p>Writing it next to the file it describes makes provenance survive a restart and travel with
     * the skill directory, so a failed sync now degrades to "the definitions we last received"
     * rather than "no capability at all".
     */
    private static final String MANAGED_MARKER = ".vortox-managed";

    @Value("${sidecar.skills-path:/app/skills}")
    private String skillsPath;

    /** Set when this sidecar is attached to a Vortox control plane. */
    @Value("${vortox.backend.url:}")
    private String vortoxBackendUrl;

    /**
     * Escape hatch for a sidecar that must keep running skills Vortox does not know about.
     *
     * <p>Off by default once a control plane is configured, because otherwise the tool surface is not
     * what Vortox authorised: anything written into the skills directory — a bind-mount in real
     * deployments — becomes callable, sidestepping the application allow-list, the tenant allow-list
     * and the agent's own {@code availableSkills}. A governed deployment should expose exactly what
     * it was given, and nothing else.
     */
    @Value("${sidecar.skills.allow-local:false}")
    private boolean allowLocalSkills;

    /** True when unmanaged skills must be hidden from the agent. */
    private boolean vortoxGoverned() {
        return vortoxBackendUrl != null && !vortoxBackendUrl.isBlank() && !allowLocalSkills;
    }

    /**
     * Whether this skill may be offered to the agent. Under a control plane, only what Vortox
     * delivered — a local file is loaded and listed (so an operator can see and remove it) but never
     * exposed as a tool.
     */
    private boolean isUsable(String tenantId, String name) {
        return !vortoxGoverned() || isVortoxManaged(tenantId, name);
    }

    /** Visible to every tenant. */
    private final Map<String, SkillDefinition> sharedSkills = new ConcurrentHashMap<>();

    /** tenantId → its own skills. Never merged across tenants. */
    private final Map<String, Map<String, SkillDefinition>> tenantSkills = new ConcurrentHashMap<>();

    /**
     * Names last written by a Vortox sync, as {@code tenantId|name} (tenantId blank for shared).
     *
     * <p>Provenance matters to the UI: a synced skill is replaced on the next sync, so editing it
     * here achieves nothing and quietly diverges from what Vortox holds. A skill that only exists on
     * this container — dropped into the skills directory by hand — is never touched by sync and is
     * genuinely editable. Without this the UI cannot tell the two apart and has to treat everything
     * the same way.
     */
    private final java.util.Set<String> vortoxManaged = ConcurrentHashMap.newKeySet();

    private static String provenanceKey(String tenantId, String name) {
        return (tenantId == null ? "" : tenantId) + "|" + name;
    }

    /** True when this skill's body came from Vortox and will be overwritten on the next sync. */
    public boolean isVortoxManaged(String tenantId, String name) {
        return vortoxManaged.contains(provenanceKey(tenantId, name))
                || vortoxManaged.contains(provenanceKey(null, name));
    }

    @PostConstruct
    public void load() {
        sharedSkills.clear();
        tenantSkills.clear();
        Path base = Path.of(skillsPath);
        if (!Files.isDirectory(base)) {
            log.warn("Skills directory not found: {}", base);
            return;
        }

        try (Stream<Path> entries = Files.list(base)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                if (TENANTS_DIR.equals(dir.getFileName().toString())) {
                    loadTenantTier(dir);
                } else {
                    loadSkill(dir, sharedSkills, null);
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan skills directory: {}", e.getMessage());
        }
        log.info("Skill registry loaded: {} shared, {} tenant(s) with private skills",
                sharedSkills.size(), tenantSkills.size());
    }

    private void loadTenantTier(Path tenantsRoot) {
        try (Stream<Path> tenants = Files.list(tenantsRoot)) {
            tenants.filter(Files::isDirectory).forEach(tenantDir -> {
                String tenantId = tenantDir.getFileName().toString();
                Map<String, SkillDefinition> forTenant =
                        tenantSkills.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());
                try (Stream<Path> skillDirs = Files.list(tenantDir)) {
                    skillDirs.filter(Files::isDirectory)
                             .forEach(dir -> loadSkill(dir, forTenant, tenantId));
                } catch (IOException e) {
                    log.error("Failed to scan skills for tenant {}: {}", tenantId, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Failed to scan tenant skills directory: {}", e.getMessage());
        }
    }

    private void loadSkill(Path skillDir, Map<String, SkillDefinition> target, String tenantId) {
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

            target.put(p.name(), new SkillDefinition(
                    p.name(), p.description(), p.language(), p.timeoutSeconds(), p.inputSchema(),
                    p.implementation(), content, p.producesArtifact()));

            // Restore provenance from disk. Keyed by the parsed name rather than the directory name
            // because that is what the registry and isUsable() key on — the two can differ, and the
            // mismatch is only warned about above.
            if (Files.exists(skillDir.resolve(MANAGED_MARKER))) {
                vortoxManaged.add(provenanceKey(tenantId, p.name()));
            }
            log.debug("Loaded skill: {} ({})", p.name(), tenantId == null ? "shared" : "tenant " + tenantId);
        } catch (Exception e) {
            log.error("Failed to load skill from {}: {}", skillFile, e.getMessage());
        }
    }

    /** Parsed-but-not-yet-registered view of a SKILL.md — lets save() validate before writing anything. */
    private record ParsedSkill(String name, String description, String language, int timeoutSeconds,
                                Map<String, Object> inputSchema, String implementation,
                                SkillDefinition.ProducesArtifact producesArtifact) {}

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
        SkillDefinition.ProducesArtifact producesArtifact = parseProducesArtifact(
                (Map<String, Object>) parsed.get("produces_artifact"));

        return new ParsedSkill(name, description, language, timeout, schema, implementation, producesArtifact);
    }

    /** Reads the optional {@code produces_artifact: {path_field, source}} declaration off a SKILL.md. */
    private static SkillDefinition.ProducesArtifact parseProducesArtifact(Map<String, Object> raw) {
        if (raw == null) return null;
        Object pathField = raw.get("path_field");
        if (!(pathField instanceof String pf) || pf.isBlank()) {
            log.warn("Skill declares 'produces_artifact' without a 'path_field' — ignoring it");
            return null;
        }
        String source = raw.get("source") instanceof String s && !s.isBlank() ? s : "output";
        return new SkillDefinition.ProducesArtifact(pf, source);
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    /**
     * Shared skills only. A caller that has a tenant must pass it — omitting one resolves to the
     * shared tier rather than to some other tenant's, so a forgotten tenant is a missing-skill bug,
     * never a cross-tenant leak.
     */
    public Optional<SkillDefinition> find(String name) {
        return findFor(null, name);
    }

    /**
     * Resolves {@code name} for one tenant: its own skills first, then shared. Consults exactly one
     * tenant's map — there is no fallback that could reach another tenant's copy.
     */
    public Optional<SkillDefinition> findFor(String tenantId, String name) {
        // Refused before the tier lookup: under a control plane an unmanaged skill is not a tool,
        // whichever tier it happens to sit in.
        if (!isUsable(tenantId, name)) return Optional.empty();
        if (tenantId != null) {
            SkillDefinition own = tenantSkills.getOrDefault(tenantId, Map.of()).get(name);
            if (own != null) return Optional.of(own);
        }
        return Optional.ofNullable(sharedSkills.get(name));
    }

    /** Shared skills only — see {@link #find(String)}. */
    public Collection<SkillDefinition> all() {
        return allFor(null);
    }

    /** Everything one tenant can see: its own skills shadowing shared ones of the same name. */
    public Collection<SkillDefinition> allFor(String tenantId) {
        Map<String, SkillDefinition> merged = new LinkedHashMap<>(sharedSkills);
        if (tenantId != null) {
            Map<String, SkillDefinition> own = tenantSkills.getOrDefault(tenantId, Map.of());
            own.forEach((name, def) -> {
                if (merged.put(name, def) != null) {
                    log.info("Tenant {} overrides shared skill '{}' with its own definition", tenantId, name);
                }
            });
        }
        if (vortoxGoverned()) {
            int before = merged.size();
            merged.keySet().removeIf(name -> !isVortoxManaged(tenantId, name));
            if (before != merged.size()) {
                log.info("Withheld {} local skill(s) from tenant {}: this sidecar is governed by "
                        + "Vortox and offers only what Vortox delivered", before - merged.size(), tenantId);
            }
            // Withholding *everything* is different in kind from withholding a few local extras: the
            // agent silently loses every tool and answers from its own knowledge instead, which reads
            // to a user as the assistant arbitrarily forgetting what it can do. It means no sync has
            // ever succeeded here, so say so plainly rather than leaving it to be inferred from a
            // skills=[] line.
            if (before > 0 && merged.isEmpty()) {
                log.error("No skills are available to tenant {}: {} definition(s) are loaded from disk "
                        + "but none is marked as delivered by Vortox, so all are withheld. The agent "
                        + "will run with no tools. This means no skill sync has succeeded — check the "
                        + "sync log above for an HTTP error (a pooled key needs a tenant code).",
                        tenantId, before);
            }
        }
        return Collections.unmodifiableCollection(merged.values());
    }

    /** Loaded from disk but withheld, so the UI can show an operator what is being ignored. */
    public Collection<String> unusableNames(String tenantId) {
        if (!vortoxGoverned()) return List.of();
        Set<String> names = new LinkedHashSet<>(sharedSkills.keySet());
        if (tenantId != null) names.addAll(tenantSkills.getOrDefault(tenantId, Map.of()).keySet());
        names.removeIf(name -> isVortoxManaged(tenantId, name));
        return names;
    }

    /** Shared skills only — see {@link #find(String)}. */
    public List<SkillDefinition> subset(List<String> names) {
        return subsetFor(null, names);
    }

    /**
     * The named skills as one tenant sees them, or everything it can see when {@code names} is
     * empty. Unknown names are dropped, exactly as before — a tenant asking for a skill it has no
     * access to gets nothing rather than someone else's.
     */
    public List<SkillDefinition> subsetFor(String tenantId, List<String> names) {
        if (names == null || names.isEmpty()) return new ArrayList<>(allFor(tenantId));
        return names.stream()
                .map(name -> findFor(tenantId, name).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    /** Number of shared skills. */
    public int count() {
        return sharedSkills.size();
    }

    /** Number of skills one tenant can see, counting its overrides once. */
    public int countFor(String tenantId) {
        return allFor(tenantId).size();
    }

    /** Skill names this tenant defines itself, for surfacing overrides in the UI. */
    public Set<String> tenantOwnedNames(String tenantId) {
        if (tenantId == null) return Set.of();
        return Set.copyOf(tenantSkills.getOrDefault(tenantId, Map.of()).keySet());
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
        return save(null, name, content);
    }

    /**
     * Records that this skill came from a Vortox sync rather than a local upload, so the UI can stop
     * offering to edit something the next sync will overwrite.
     */
    public SkillDefinition saveFromVortox(String tenantId, String name, String content) throws IOException {
        SkillDefinition saved = save(tenantId, name, content);
        vortoxManaged.add(provenanceKey(tenantId, name));
        // Persist it too, so the next restart does not have to re-derive provenance from a sync that
        // may not succeed. Best-effort: a read-only skills mount should not fail the sync, it just
        // costs us the durability this marker buys.
        try {
            Files.writeString(skillDirFor(tenantId, name).resolve(MANAGED_MARKER),
                    "Delivered by Vortox. Edits here are overwritten by the next sync.\n");
        } catch (IOException e) {
            log.warn("Could not write the managed marker for '{}' ({}): {}. Provenance will be "
                    + "in-memory only until the next successful sync.",
                    name, tenantId == null ? "shared" : "tenant " + tenantId, e.getMessage());
        }
        return saved;
    }

    /** Saves into one tenant's private tier, or the shared tier when {@code tenantId} is null. */
    public SkillDefinition save(String tenantId, String name, String content) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");
        if (TENANTS_DIR.equals(name)) {
            throw new IllegalArgumentException("'" + TENANTS_DIR + "' is reserved and cannot be a skill name");
        }

        ParsedSkill p = parse(content);
        if (p.name() == null || p.implementation() == null) {
            throw new IllegalArgumentException("SKILL.md must define both 'name' and 'implementation'");
        }
        if (!p.name().equals(name)) {
            throw new IllegalArgumentException(
                    "Name mismatch: requested to save as '" + name + "' but the SKILL.md content declares name '"
                            + p.name() + "'. They must match.");
        }

        Path skillDir = skillDirFor(tenantId, name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);

        // A local write makes this skill locally-owned again; saveFromVortox re-establishes
        // provenance immediately after calling through here, so sync is unaffected.
        vortoxManaged.remove(provenanceKey(tenantId, p.name()));
        Files.deleteIfExists(skillDir.resolve(MANAGED_MARKER));

        SkillDefinition saved = new SkillDefinition(
                p.name(), p.description(), p.language(), p.timeoutSeconds(), p.inputSchema(),
                p.implementation(), content, p.producesArtifact());
        targetMap(tenantId).put(p.name(), saved);
        log.info("Saved skill: {} ({})", name, tenantId == null ? "shared" : "tenant " + tenantId);
        return saved;
    }

    /**
     * Delete a skill by name. Removes its directory and evicts it from the registry.
     * Returns false if the skill was not found.
     */
    public boolean delete(String name) throws IOException {
        return delete(null, name);
    }

    /**
     * Deletes from one tier only. Deleting a tenant's override does not touch the shared skill of
     * the same name — the tenant simply stops shadowing it.
     */
    public boolean delete(String tenantId, String name) throws IOException {
        Path skillDir = skillDirFor(tenantId, name);
        if (!Files.exists(skillDir)) return false;

        try (Stream<Path> walker = Files.walk(skillDir)) {
            walker.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
        targetMap(tenantId).remove(name);
        // Otherwise a deleted-then-recreated local skill would inherit the old provenance and be
        // treated as Vortox-delivered.
        vortoxManaged.remove(provenanceKey(tenantId, name));
        log.info("Deleted skill: {} ({})", name, tenantId == null ? "shared" : "tenant " + tenantId);
        return true;
    }

    /**
     * Replaces one tenant's private tier wholesale, evicting skills the backend no longer returns.
     * Scoped to the given tenant so a sync for one tenant cannot disturb another's — the reason this
     * exists rather than a global reset.
     */
    public void replaceTenantTier(String tenantId, Map<String, String> nameToContent) throws IOException {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required to replace a tenant tier");
        }
        Path tenantRoot = Path.of(skillsPath, TENANTS_DIR, sanitizeSegment(tenantId));
        Set<String> keep = nameToContent.keySet();

        // Build the replacement tier off to the side, then publish it with a single put.
        //
        // This used to mutate the live map in place — delete the stale entries, then re-save the
        // rest one at a time — which left a window, on every five-minute poll and for every tenant
        // seen in traffic, where a concurrent run resolved a half-populated tier and got a subset of
        // the tools it should have had. Same question, different answer, depending on timing.
        // Writing files still happens one at a time (they are not transactional), but what a run
        // *sees* now flips from the whole old tier to the whole new one.
        Map<String, SkillDefinition> next = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> e : nameToContent.entrySet()) {
            try {
                next.put(e.getKey(), writeTenantSkill(tenantId, e.getKey(), e.getValue()));
            } catch (Exception ex) {
                log.warn("Tenant {}: failed to save synced skill '{}': {}", tenantId, e.getKey(), ex.getMessage());
                // Keep whatever we had for this one rather than dropping it: a single bad definition
                // should not cost the tenant a working tool.
                SkillDefinition previous = tenantSkills.getOrDefault(tenantId, Map.of()).get(e.getKey());
                if (previous != null) next.put(e.getKey(), previous);
            }
        }

        Map<String, SkillDefinition> previousTier = tenantSkills.put(tenantId, next);

        // Only once the new tier is live: removing the files a run can no longer reach is safe,
        // whereas doing it first is exactly the gap described above.
        if (previousTier != null) {
            for (String stale : Set.copyOf(previousTier.keySet())) {
                if (!keep.contains(stale)) {
                    try {
                        deleteFiles(tenantId, stale);
                        vortoxManaged.remove(provenanceKey(tenantId, stale));
                    } catch (IOException ex) {
                        log.warn("Tenant {}: failed to remove evicted skill '{}': {}",
                                tenantId, stale, ex.getMessage());
                    }
                }
            }
        }

        if (!Files.exists(tenantRoot) && !nameToContent.isEmpty()) {
            log.warn("Tenant {} skill directory missing after sync: {}", tenantId, tenantRoot);
        }
    }

    /**
     * Writes one tenant skill to disk and returns its definition <em>without</em> touching the live
     * tier — the piece {@link #replaceTenantTier} needs to stage a whole tier before publishing it.
     */
    private SkillDefinition writeTenantSkill(String tenantId, String name, String content) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content is required");

        ParsedSkill p = parse(content);
        if (p.name() == null || p.implementation() == null) {
            throw new IllegalArgumentException("SKILL.md must define both 'name' and 'implementation'");
        }
        if (!p.name().equals(name)) {
            throw new IllegalArgumentException("Name mismatch: requested to save as '" + name
                    + "' but the SKILL.md content declares name '" + p.name() + "'. They must match.");
        }

        Path skillDir = skillDirFor(tenantId, name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
        vortoxManaged.add(provenanceKey(tenantId, name));
        try {
            Files.writeString(skillDir.resolve(MANAGED_MARKER),
                    "Delivered by Vortox. Edits here are overwritten by the next sync.\n");
        } catch (IOException e) {
            log.warn("Could not write the managed marker for tenant {} skill '{}': {}",
                    tenantId, name, e.getMessage());
        }
        return new SkillDefinition(p.name(), p.description(), p.language(), p.timeoutSeconds(),
                p.inputSchema(), p.implementation(), content, p.producesArtifact());
    }

    /** Removes a skill's directory without touching the in-memory tier. */
    private void deleteFiles(String tenantId, String name) throws IOException {
        Path skillDir = skillDirFor(tenantId, name);
        if (!Files.exists(skillDir)) return;
        try (Stream<Path> walker = Files.walk(skillDir)) {
            walker.sorted(Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        }
    }

    /** True once this tenant's private tier has been populated, so sync can be lazy. */
    public boolean hasTenantTier(String tenantId) {
        return tenantId != null && tenantSkills.containsKey(tenantId);
    }

    private Map<String, SkillDefinition> targetMap(String tenantId) {
        return tenantId == null
                ? sharedSkills
                : tenantSkills.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>());
    }

    private Path skillDirFor(String tenantId, String name) {
        return tenantId == null
                ? Path.of(skillsPath, sanitizeSegment(name))
                : Path.of(skillsPath, TENANTS_DIR, sanitizeSegment(tenantId), sanitizeSegment(name));
    }

    /**
     * Keeps a tenant id or skill name to a single directory name. Both reach here from outside this
     * process — a tenant id from a request header, a skill name from a synced SKILL.md — so neither
     * may contain a separator that would write outside its own tier.
     */
    private static String sanitizeSegment(String raw) {
        String safe = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        if (safe.isBlank()) throw new IllegalArgumentException("Unusable path segment: '" + raw + "'");
        return safe;
    }
}
