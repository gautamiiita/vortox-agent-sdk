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
 * When a control plane is configured, the agent's tool surface must be exactly what Vortox
 * delivered.
 *
 * <p>The skills directory is a host bind-mount in real deployments, so anything written into it was
 * previously loaded and callable. That sidesteps every control above it — the application
 * allow-list, the tenant allow-list and the agent's own {@code availableSkills} — because those
 * govern what Vortox *sends*, not what happens to be on disk. A governed sidecar therefore withholds
 * whatever it was not given.
 *
 * <p>Withheld, not unloaded: the skill is still listed so an operator can see it is being ignored
 * rather than watch it vanish with no explanation.
 */
class SkillRegistryGovernedTest {

    private SkillRegistry registry;
    private Path skillsRoot;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        skillsRoot = tmp.resolve("skills");
        registry = new SkillRegistry();
        ReflectionTestUtils.setField(registry, "skillsPath", skillsRoot.toString());
    }

    private void governed(boolean on) {
        ReflectionTestUtils.setField(registry, "vortoxBackendUrl", on ? "http://vortox:8080" : "");
        ReflectionTestUtils.setField(registry, "allowLocalSkills", false);
    }

    private static String skillMd(String name) {
        return "name: " + name + "\ndescription: t\nlanguage: python\ntimeout_seconds: 30\n"
                + "implementation: |\n  print('x')\n";
    }

    private void writeLocal(String name) throws IOException {
        Path dir = skillsRoot.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), skillMd(name));
    }

    @Test
    @DisplayName("a governed sidecar does not offer a skill Vortox never sent")
    void withholdsLocalSkillWhenGoverned() throws IOException {
        writeLocal("oracle_query");        // dropped into the bind-mount, unknown to Vortox
        governed(true);
        registry.load();

        // Loaded from disk, but not a tool: it would otherwise bypass every allow-list above it,
        // and it receives no secrets because Vortox does not know it exists.
        assertThat(registry.findFor("CUBE", "oracle_query")).isEmpty();
        assertThat(registry.allFor("CUBE")).isEmpty();
        assertThat(registry.unusableNames("CUBE")).containsExactly("oracle_query");
    }

    @Test
    @DisplayName("a skill delivered by Vortox is offered normally")
    void offersManagedSkill() throws IOException {
        governed(true);
        registry.load();
        registry.saveFromVortox(null, "execute_oracle_sql", skillMd("execute_oracle_sql"));

        assertThat(registry.findFor("CUBE", "execute_oracle_sql")).isPresent();
        assertThat(registry.allFor("CUBE")).hasSize(1);
        assertThat(registry.unusableNames("CUBE")).isEmpty();
    }

    @Test
    @DisplayName("an ungoverned sidecar still runs its local skills")
    void localSkillsWorkWithoutAControlPlane() throws IOException {
        // A standalone deployment has no Vortox to deliver anything; withholding here would leave it
        // with no tools at all.
        writeLocal("oracle_query");
        governed(false);
        registry.load();

        assertThat(registry.findFor(null, "oracle_query")).isPresent();
        assertThat(registry.unusableNames(null)).isEmpty();
    }

    @Test
    @DisplayName("the escape hatch restores local skills under a control plane")
    void allowLocalOptsBackIn() throws IOException {
        writeLocal("oracle_query");
        ReflectionTestUtils.setField(registry, "vortoxBackendUrl", "http://vortox:8080");
        ReflectionTestUtils.setField(registry, "allowLocalSkills", true);
        registry.load();

        assertThat(registry.findFor("CUBE", "oracle_query")).isPresent();
    }

    @Test
    @DisplayName("a local skill cannot shadow a managed one to smuggle in a different body")
    void localCannotShadowManaged() throws IOException {
        // The sharp case: same name, different implementation. Under governance the local copy must
        // never be what runs, or the allow-list would pass while the body is someone else's.
        writeLocal("execute_oracle_sql");
        governed(true);
        registry.load();

        assertThat(registry.findFor("CUBE", "execute_oracle_sql")).isEmpty();

        registry.saveFromVortox(null, "execute_oracle_sql", skillMd("execute_oracle_sql"));
        assertThat(registry.findFor("CUBE", "execute_oracle_sql")).isPresent();
    }
}
