package com.vortox.sidecar.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-line description of what a tool was asked to do, shown in the progress trail.
 *
 * <p>Without it the trail only knows tool names, so an agent working iteratively produces a column
 * of identical rows — eight "Querying the database" entries that say nothing about what was queried
 * and read as eight things going wrong rather than one investigation proceeding.
 */
class AgentStreamRegistrySummaryTest {

    @Test
    @DisplayName("prefers the parameter that identifies the work")
    void picksTheInformativeParameter() {
        assertThat(AgentStreamRegistry.summariseParams(Map.of(
                "max_rows", 5000,
                "sql", "SELECT COUNT(*) FROM CONTACT WHERE INSTIT_CODE = 'CUBE'")))
                .isEqualTo("SELECT COUNT(*) FROM CONTACT WHERE INSTIT_CODE = 'CUBE'");

        assertThat(AgentStreamRegistry.summariseParams(Map.of("path", "/app/workspaces/report.html")))
                .isEqualTo("/app/workspaces/report.html");
    }

    /** A formatted statement spanning a dozen lines cannot be a single progress row. */
    @Test
    @DisplayName("collapses whitespace and caps the length")
    void normalisesLongMultilineValues() {
        String sql = "SELECT c.CONTACT_ID,\n       c.EMAIL\n  FROM CONTACT c\n"
                   + " WHERE c.INSTIT_CODE = 'CUBE'\n   AND c.STATUS = 'ACTIVE'\n"
                   + " ORDER BY c.CREATION_DATE DESC, c.CONTACT_ID ASC, c.EMAIL ASC";

        String summary = AgentStreamRegistry.summariseParams(Map.of("sql", sql));

        assertThat(summary).doesNotContain("\n");
        assertThat(summary).startsWith("SELECT c.CONTACT_ID, c.EMAIL FROM CONTACT c");
        assertThat(summary.length()).isLessThanOrEqualTo(120);
        assertThat(summary).endsWith("…");
    }

    @Test
    @DisplayName("falls back to any usable scalar so an unknown tool still says something")
    void namesTheParameterWhenItIsNotOneWeKnow() {
        assertThat(AgentStreamRegistry.summariseParams(Map.of("channel", "#ops")))
                .isEqualTo("channel: #ops");
    }

    /**
     * A progress trail is often the part of a screen that gets shared or screenshotted, so anything
     * named like a credential is skipped whatever the tool calls it.
     */
    @Test
    @DisplayName("never shows a credential-shaped parameter")
    void skipsSecrets() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("password", "hunter2");
        params.put("api_key", "sk-ant-oat01-secret");
        params.put("token", "eyJhbGci");
        params.put("url", "https://example.test/report");

        String summary = AgentStreamRegistry.summariseParams(params);

        assertThat(summary).isEqualTo("https://example.test/report");
        assertThat(summary).doesNotContain("hunter2").doesNotContain("sk-ant").doesNotContain("eyJ");
    }

    @Test
    @DisplayName("a secret-only call reports nothing rather than leaking it")
    void yieldsNothingWhenEverythingIsSecret() {
        assertThat(AgentStreamRegistry.summariseParams(Map.of("password", "hunter2"))).isNull();
    }

    @Test
    @DisplayName("structure is not a progress line")
    void ignoresMapsAndLists() {
        assertThat(AgentStreamRegistry.summariseParams(Map.of("rows", List.of(1, 2, 3)))).isNull();
        assertThat(AgentStreamRegistry.summariseParams(Map.of("body", Map.of("a", "b")))).isNull();
    }

    @Test
    @DisplayName("nothing to say for an empty or blank call")
    void handlesEmptyInput() {
        assertThat(AgentStreamRegistry.summariseParams(null)).isNull();
        assertThat(AgentStreamRegistry.summariseParams(Map.of())).isNull();
        assertThat(AgentStreamRegistry.summariseParams(Map.of("sql", "   "))).isNull();
    }
}
