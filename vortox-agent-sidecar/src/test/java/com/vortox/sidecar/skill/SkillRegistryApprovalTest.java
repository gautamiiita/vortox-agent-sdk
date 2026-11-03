package com.vortox.sidecar.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code requires_approval} is part of a tool's definition, read by the one parser every source goes
 * through — a SKILL.md on disk, the skills screen, and a tool configured in Vortox and synced down.
 * So a tool that needs a person's approval says so wherever it was configured, and the sidecar
 * honours it the same way for all three.
 */
class SkillRegistryApprovalTest {

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
        ReflectionTestUtils.setField(r, "allowLocalSkills", true);
        return r;
    }

    private static String skillMd(String name, String approvalLine) {
        return "name: " + name + "\ndescription: t\nlanguage: python\ntimeout_seconds: 30\n"
                + (approvalLine == null ? "" : approvalLine + "\n")
                + "implementation: |\n  print('x')\n";
    }

    @Test
    @DisplayName("a tool synced from Vortox carries its approval text")
    void syncedFromVortox() throws Exception {
        SkillDefinition s = registry.saveFromVortox(null, "declare",
                skillMd("declare", "requires_approval: \"Declare on your behalf: {purpose}\""));

        assertThat(s.requiresApproval()).isEqualTo("Declare on your behalf: {purpose}");
        assertThat(registry.findFor(null, "declare")).get()
                .extracting(SkillDefinition::requiresApproval).isEqualTo("Declare on your behalf: {purpose}");
    }

    @Test
    @DisplayName("and keeps it across a restart, read back off disk")
    void survivesReload() throws Exception {
        registry.saveFromVortox(null, "declare", skillMd("declare", "requires_approval: \"Declare {purpose}\""));

        SkillRegistry restarted = newRegistry();
        restarted.load();

        assertThat(restarted.findFor(null, "declare")).get()
                .extracting(SkillDefinition::requiresApproval).isEqualTo("Declare {purpose}");
    }

    @Test
    @DisplayName("a skill saved locally declares it the same way")
    void savedLocally() throws Exception {
        SkillDefinition s = registry.save("delete_rows", skillMd("delete_rows", "requires_approval: \"Delete {table}\""));
        assertThat(s.requiresApproval()).isEqualTo("Delete {table}");
    }

    @Test
    @DisplayName("`requires_approval: true` asks with a default text; absent or false asks nothing")
    void booleanForms() throws Exception {
        assertThat(registry.save("a", skillMd("a", "requires_approval: true")).requiresApproval()).isEmpty();
        assertThat(registry.save("b", skillMd("b", "requires_approval: false")).requiresApproval()).isNull();
        assertThat(registry.save("c", skillMd("c", null)).requiresApproval()).isNull();
    }

    @Test
    @DisplayName("the approval text never reaches the LLM's tool definition")
    void notInToolDefinition() throws Exception {
        SkillDefinition s = registry.save("d", skillMd("d", "requires_approval: \"x\""));
        assertThat(s.toToolDefinition()).doesNotContainKey("requires_approval");
    }
}
