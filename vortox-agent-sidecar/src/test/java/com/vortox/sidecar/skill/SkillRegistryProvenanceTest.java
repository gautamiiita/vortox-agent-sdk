package com.vortox.sidecar.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provenance has to outlive the process that learned it.
 *
 * <p>Under a control plane a skill is only offered as a tool if Vortox delivered it, and that fact
 * used to live purely in memory — set when a sync wrote the skill, never restored when the registry
 * read the same file back off disk. So a restart, or a sync that failed (a pooled key refused for
 * want of a tenant code, a network blip), left every definition loaded, counted in the logs, and
 * silently withheld: the agent ran with no tools at all and answered from its own knowledge. To a
 * user that looks like the assistant arbitrarily forgetting what it can do, which is close to
 * impossible to diagnose from the outside.
 *
 * <p>Writing the marker beside the SKILL.md it describes makes a failed sync degrade to "the
 * definitions we last received" instead of "no capability at all", while still withholding files
 * that were only ever dropped into the bind-mount by hand.
 */
class SkillRegistryProvenanceTest {

    private SkillRegistry registry;
    private Path skillsRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        skillsRoot = tmp.resolve("skills");
        registry = newRegistry();
    }

    private SkillRegistry newRegistry() {
        SkillRegistry r = new SkillRegistry();
        ReflectionTestUtils.setField(r, "skillsPath", skillsRoot.toString());
        ReflectionTestUtils.setField(r, "vortoxBackendUrl", "http://vortox:8080");
        ReflectionTestUtils.setField(r, "allowLocalSkills", false);
        return r;
    }

    private static String skillMd(String name) {
        return "name: " + name + "\ndescription: t\nlanguage: python\ntimeout_seconds: 30\n"
                + "implementation: |\n  print('x')\n";
    }

    @Test
    @DisplayName("a synced skill is still offered after a restart, even if no sync succeeds again")
    void provenanceSurvivesRestart() throws IOException {
        registry.saveFromVortox(null, "oracle_query", skillMd("oracle_query"));
        assertThat(registry.allFor(null)).extracting(SkillDefinition::name).contains("oracle_query");

        // A fresh registry over the same directory: the restart case, with no sync following it.
        SkillRegistry restarted = newRegistry();
        restarted.load();

        assertThat(restarted.allFor(null))
                .as("a delivered skill must remain usable when the next sync never lands")
                .extracting(SkillDefinition::name)
                .contains("oracle_query");
    }

    @Test
    @DisplayName("a hand-placed skill is still withheld after a restart")
    void locallyDroppedSkillStaysWithheld() throws IOException {
        Path dir = skillsRoot.resolve("rogue");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd("rogue"));

        SkillRegistry restarted = newRegistry();
        restarted.load();

        assertThat(restarted.allFor(null))
                .as("persisting provenance must not turn the bind-mount into an open door")
                .extracting(SkillDefinition::name)
                .doesNotContain("rogue");
        assertThat(restarted.unusableNames(null)).contains("rogue");
    }

    @Test
    @DisplayName("a local overwrite of a delivered skill drops its Vortox provenance")
    void localSaveClearsProvenance() throws IOException {
        registry.saveFromVortox(null, "oracle_query", skillMd("oracle_query"));
        registry.save(null, "oracle_query", skillMd("oracle_query"));

        SkillRegistry restarted = newRegistry();
        restarted.load();

        assertThat(restarted.allFor(null))
                .as("once edited locally it is no longer what Vortox delivered")
                .extracting(SkillDefinition::name)
                .doesNotContain("oracle_query");
    }

    @Test
    @DisplayName("a tenant's synced tier survives a restart too")
    void tenantProvenanceSurvivesRestart() throws IOException {
        registry.replaceTenantTier("CUBE", java.util.Map.of("oracle_query", skillMd("oracle_query")));

        SkillRegistry restarted = newRegistry();
        restarted.load();

        assertThat(restarted.allFor("CUBE")).extracting(SkillDefinition::name).contains("oracle_query");
        assertThat(restarted.allFor("OTHER"))
                .as("one tenant's tier must not leak into another's")
                .extracting(SkillDefinition::name)
                .doesNotContain("oracle_query");
    }

    @Test
    @DisplayName("replacing a tier evicts what the backend no longer sends")
    void tierReplacementEvictsStaleSkills() throws IOException {
        registry.replaceTenantTier("CUBE", java.util.Map.of(
                "oracle_query", skillMd("oracle_query"),
                "oracle_schema", skillMd("oracle_schema")));
        registry.replaceTenantTier("CUBE", java.util.Map.of("oracle_query", skillMd("oracle_query")));

        assertThat(registry.allFor("CUBE")).extracting(SkillDefinition::name)
                .contains("oracle_query")
                .doesNotContain("oracle_schema");
    }
}
