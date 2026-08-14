package com.vortox.sidecar.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the page and changing it are separate capabilities.
 *
 * <p>The structure used to be appended only when {@code allowPageScripts} was on, which had two
 * consequences. There was no read-only mode at all — "explain this screen", the most useful and
 * least dangerous thing an embedded agent can do, required enabling arbitrary script execution
 * first. And page actions were broken in their intended configuration: their instructions tell the
 * model to take every selector from "the page structure above" and never invent one, while the
 * structure itself was withheld unless scripts were also enabled, leaving the model no option but
 * to guess — and a guessed selector silently changes the wrong part of the page.
 *
 * <p>Also covers the context summary the widget renders its chips from, which is reported by the
 * server precisely because the browser cannot know what the host's enricher added or stripped.
 */
class AgentControllerPageContextTest {

    private static AgentChatRequest request(Boolean allowScripts, String pageStructure,
                                            List<String> actions, Map<String, Object> context,
                                            String tenantCode, String surface) {
        return new AgentChatRequest(
                "what is missing on this form?", null, context,
                allowScripts, pageStructure,
                actions, null,
                null, null, null, null, null, tenantCode, surface);
    }

    /** Mirrors how the controller assembles runtime instructions for a turn. */
    private static String runtimeInstructionsFor(AgentChatRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.pageApiDescription() != null && !request.pageApiDescription().isBlank()) {
            sb.append("\n\n## Host Page Structure\n").append(request.pageApiDescription());
        }
        AgentController.appendPageActionInstructions(sb, request);
        return sb.toString();
    }

    @Test
    @DisplayName("page actions receive the structure their selectors must come from")
    void pageActionsGetTheStructureWithoutEnablingScripts() {
        String instructions = runtimeInstructionsFor(request(
                false, "### Form fields\n#status (select) label:\"Status\" empty",
                List.of("highlight", "fill"), null, null, null));

        assertThat(instructions)
                .contains("## Host Page Structure")
                .contains("#status")
                .contains("Available actions: highlight, fill")
                .doesNotContain("## Page Script Capability");
    }

    @Test
    @DisplayName("structure alone is a valid configuration — no write capability implied")
    void readOnlyPageContextIsPossible() {
        String instructions = runtimeInstructionsFor(request(
                false, "### Page headings\nh1: Order detail", null, null, null, null));

        assertThat(instructions).contains("Order detail");
        assertThat(instructions).doesNotContain("Page Actions");
        assertThat(instructions).doesNotContain("Page Script Capability");
    }

    // ── Context summary ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary reports server-added context the page never saw")
    void summaryIncludesEnricherAddedFields() {
        AgentChatRequest r = request(false, "### Page headings\nh1: Orders", null,
                Map.of("authenticatedUser", "SUP#GSR", "institutionCode", "CUBE"),
                "CUBE", "tnam:order-detail");

        List<Map<String, String>> summary =
                AgentController.describeEffectiveContext(r, runtimeInstructionsFor(r));

        assertThat(summary).anySatisfy(item -> {
            assertThat(item.get("key")).isEqualTo("authenticatedUser");
            assertThat(item.get("value")).isEqualTo("SUP#GSR");
        });
        assertThat(summary).anySatisfy(item -> assertThat(item.get("key")).isEqualTo("surface"));
        assertThat(summary).anySatisfy(item -> {
            assertThat(item.get("key")).isEqualTo("tenant");
            assertThat(item.get("source")).isEqualTo("server");
        });
    }

    /**
     * Absent page context is a fact about the conversation, so the chip says "not sent" rather than
     * disappearing — the user can tell the difference between "the agent cannot see this screen" and
     * "the indicator has nothing to report".
     */
    @Test
    @DisplayName("page context is reported as not sent, not omitted")
    void summaryStatesWhenNoPageContextWasSent() {
        AgentChatRequest r = request(false, null, null, null, null, null);

        List<Map<String, String>> summary =
                AgentController.describeEffectiveContext(r, runtimeInstructionsFor(r));

        assertThat(summary).anySatisfy(item -> {
            assertThat(item.get("key")).isEqualTo("pageContext");
            assertThat(item.get("value")).isEqualTo("not sent");
        });
    }

    @Test
    @DisplayName("script execution is surfaced, since it is a standing risk rather than a detail")
    void summaryFlagsScriptExecution() {
        AgentChatRequest r = request(true, "### Page headings\nh1: Orders", null, null, null, null);

        assertThat(AgentController.describeEffectiveContext(r, runtimeInstructionsFor(r)))
                .anySatisfy(item -> {
                    assertThat(item.get("key")).isEqualTo("pageScripts");
                    assertThat(item.get("value")).isEqualTo("enabled");
                });
    }

    @Test
    @DisplayName("blank context values are dropped rather than shown as empty chips")
    void summarySkipsBlankValues() {
        AgentChatRequest r = request(false, null, null,
                Map.of("authenticatedUser", "  ", "application", "TNAM"), null, null);

        List<Map<String, String>> summary =
                AgentController.describeEffectiveContext(r, runtimeInstructionsFor(r));

        assertThat(summary).noneSatisfy(item ->
                assertThat(item.get("key")).isEqualTo("authenticatedUser"));
        assertThat(summary).anySatisfy(item ->
                assertThat(item.get("key")).isEqualTo("application"));
    }
}