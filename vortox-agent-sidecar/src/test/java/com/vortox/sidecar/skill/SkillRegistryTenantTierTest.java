package com.vortox.sidecar.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two-tier skill isolation in a pooled sidecar.
 *
 * <p>The tiers mirror what Vortox already models — {@code Skill} is filtered with
 * {@code tenant_id = :tenantId OR tenant_id IS NULL}, so a null tenant is the shared product
 * toolkit and a non-null one is private. The property that matters here is the negative one: one
 * tenant must never resolve another's skill. A shared skill being visible to everyone is by design;
 * a neighbouring tenant's body being reachable is the bug these tests exist to prevent.
 */
class SkillRegistryTenantTierTest {

    private SkillRegistry registry;
    private Path skillsRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        skillsRoot = tmp.resolve("skills");
        registry = new SkillRegistry();
        ReflectionTestUtils.setField(registry, "skillsPath", skillsRoot.toString());
    }

    private static String skillMd(String name, String marker) {
        return "name: " + name + "\n"
                + "description: test skill\n"
                + "language: python\n"
                + "timeout_seconds: 30\n"
                + "implementation: |\n"
                + "  print('" + marker + "')\n";
    }

    private void writeShared(String name, String marker) throws IOException {
        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name, marker));
    }

    private void writeTenant(String tenantId, String name, String marker) throws IOException {
        Path dir = skillsRoot.resolve("tenants").resolve(tenantId).resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name, marker));
    }

    @Test
    void loadsSharedSkillsFromTheExistingFlatLayout() throws IOException {
        // Shared skills deliberately keep the original flat path: the skills directory is a host
        // bind-mount in real deployments, so relocating existing directories would strand them.
        writeShared("oracle_query", "shared-body");
        registry.load();

        assertThat(registry.findFor(null, "oracle_query")).isPresent();
        assertThat(registry.findFor("tenant-cube", "oracle_query")).isPresent();
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    void oneTenantCannotResolveAnotherTenantsPrivateSkill() throws IOException {
        writeTenant("tenant-cube", "cube_only", "cube-body");
        writeTenant("tenant-arena", "arena_only", "arena-body");
        registry.load();

        assertThat(registry.findFor("tenant-cube", "cube_only")).isPresent();
        assertThat(registry.findFor("tenant-cube", "arena_only")).isEmpty();
        assertThat(registry.findFor("tenant-arena", "cube_only")).isEmpty();
    }

    @Test
    void aTenantlessLookupNeverReachesAnyTenantsPrivateSkill() throws IOException {
        writeTenant("tenant-cube", "cube_only", "cube-body");
        registry.load();

        // Forgetting to pass a tenant must degrade to shared-only — a missing skill, never someone
        // else's. This is why the no-tenant overloads mean "shared" rather than "everything".
        assertThat(registry.find("cube_only")).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void aTenantSeesSharedAndItsOwnButNothingElse() throws IOException {
        writeShared("file_read", "shared-body");
        writeTenant("tenant-cube", "cube_only", "cube-body");
        writeTenant("tenant-arena", "arena_only", "arena-body");
        registry.load();

        List<String> visible = registry.allFor("tenant-cube").stream()
                .map(SkillDefinition::name).sorted().toList();

        assertThat(visible).containsExactly("cube_only", "file_read");
        assertThat(registry.countFor("tenant-cube")).isEqualTo(2);
    }

    @Test
    void aTenantOverridesASharedSkillOfTheSameNameWithItsOwnBody() throws IOException {
        writeShared("oracle_query", "shared-body");
        writeTenant("tenant-cube", "oracle_query", "cube-custom-body");
        registry.load();

        assertThat(registry.findFor("tenant-cube", "oracle_query"))
                .get().extracting(SkillDefinition::implementation)
                .asString().contains("cube-custom-body");

        // Shadowing is scoped: another tenant, and the shared tier itself, are unaffected.
        assertThat(registry.findFor("tenant-arena", "oracle_query"))
                .get().extracting(SkillDefinition::implementation)
                .asString().contains("shared-body");
        assertThat(registry.findFor(null, "oracle_query"))
                .get().extracting(SkillDefinition::implementation)
                .asString().contains("shared-body");

        // Counted once, not twice.
        assertThat(registry.countFor("tenant-cube")).isEqualTo(1);
        assertThat(registry.tenantOwnedNames("tenant-cube")).containsExactly("oracle_query");
    }

    @Test
    void subsetForNarrowsWithinTheTenantsOwnVisibleSet() throws IOException {
        writeShared("file_read", "shared-body");
        writeTenant("tenant-cube", "cube_only", "cube-body");
        writeTenant("tenant-arena", "arena_only", "arena-body");
        registry.load();

        // Asking for another tenant's skill by name yields nothing rather than that tenant's body.
        List<String> got = registry.subsetFor("tenant-cube", List.of("file_read", "arena_only"))
                .stream().map(SkillDefinition::name).toList();

        assertThat(got).containsExactly("file_read");
    }

    @Test
    void savingForATenantWritesUnderThatTenantOnly() throws IOException {
        registry.load();
        registry.save("tenant-cube", "cube_only", skillMd("cube_only", "cube-body"));

        assertThat(skillsRoot.resolve("tenants/tenant-cube/cube_only/SKILL.md")).exists();
        assertThat(skillsRoot.resolve("cube_only")).doesNotExist();
        assertThat(registry.findFor("tenant-arena", "cube_only")).isEmpty();
    }

    @Test
    void deletingATenantOverrideLeavesTheSharedSkillIntact() throws IOException {
        writeShared("oracle_query", "shared-body");
        writeTenant("tenant-cube", "oracle_query", "cube-custom-body");
        registry.load();

        assertThat(registry.delete("tenant-cube", "oracle_query")).isTrue();

        // The tenant stops shadowing and falls back to shared, rather than losing the skill.
        assertThat(registry.findFor("tenant-cube", "oracle_query"))
                .get().extracting(SkillDefinition::implementation)
                .asString().contains("shared-body");
        assertThat(skillsRoot.resolve("oracle_query/SKILL.md")).exists();
    }

    @Test
    void replacingATenantTierEvictsItsStaleSkillsAndTouchesNoOtherTenant() throws IOException {
        writeTenant("tenant-cube", "keep_me", "body-1");
        writeTenant("tenant-cube", "drop_me", "body-2");
        writeTenant("tenant-arena", "arena_only", "arena-body");
        registry.load();

        registry.replaceTenantTier("tenant-cube", Map.of("keep_me", skillMd("keep_me", "body-1-updated")));

        assertThat(registry.findFor("tenant-cube", "keep_me")).isPresent();
        assertThat(registry.findFor("tenant-cube", "drop_me")).isEmpty();
        // A sync for one tenant must not disturb another's tier — the reason this is scoped rather
        // than a global reset.
        assertThat(registry.findFor("tenant-arena", "arena_only")).isPresent();
    }

    @Test
    void aTenantIdContainingSeparatorsCannotEscapeItsOwnTier() throws IOException {
        registry.load();
        // The tenant code arrives from a request header, so it must not be able to write elsewhere.
        registry.save("../../etc", "evil", skillMd("evil", "nope"));

        assertThat(skillsRoot.resolve("tenants")).exists();
        try (var walk = Files.walk(skillsRoot)) {
            assertThat(walk.allMatch(p -> p.startsWith(skillsRoot))).isTrue();
        }
        assertThat(Files.exists(skillsRoot.getParent().resolve("etc"))).isFalse();
    }

    @Test
    void theReservedTenantsDirectoryCannotBeUsedAsASkillName() {
        assertThatThrownBy(() -> registry.save(null, SkillRegistry.TENANTS_DIR, skillMd("tenants", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void tenantTierPresenceIsReportedSoSyncCanBeLazy() throws IOException {
        writeTenant("tenant-cube", "cube_only", "cube-body");
        registry.load();

        assertThat(registry.hasTenantTier("tenant-cube")).isTrue();
        assertThat(registry.hasTenantTier("tenant-never-seen")).isFalse();
    }
}
