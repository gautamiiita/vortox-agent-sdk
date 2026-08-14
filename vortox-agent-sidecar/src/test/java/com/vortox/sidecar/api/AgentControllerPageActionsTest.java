package com.vortox.sidecar.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system-prompt half of page actions.
 *
 * <p>What the prompt says is the only thing steering the model towards the constrained envelope
 * instead of the arbitrary JavaScript that {@code allowPageScripts} still accepts. The browser
 * refuses anything malformed either way, so a bad prompt costs correctness rather than safety —
 * but a model that never learns the envelope produces an assistant that silently does nothing.
 */
class AgentControllerPageActionsTest {

    private static AgentChatRequest request(List<String> actions, String lastResults) {
        return new AgentChatRequest(
                "do something", null, null,
                null, null,
                actions, lastResults,
                null, null, null, null, null, null, null);
    }

    private static String promptFor(AgentChatRequest request) {
        StringBuilder sb = new StringBuilder();
        AgentController.appendPageActionInstructions(sb, request);
        return sb.toString();
    }

    @Test
    void saysNothingWhenTheWidgetOffersNoActions() {
        assertThat(promptFor(request(null, null))).isEmpty();
        assertThat(promptFor(request(List.of(), null))).isEmpty();
    }

    @Test
    void namesEveryActionTheWidgetOffers() {
        String prompt = promptFor(request(List.of("highlight", "fill"), null));

        assertThat(prompt).contains("Available actions: highlight, fill");
    }

    @Test
    void specifiesTheTaggedEnvelopeRatherThanAPlainJsonBlock() {
        String prompt = promptFor(request(List.of("fill"), null));

        // A plain ```json fence would collide with JSON the model writes to explain something to
        // the user, and the widget would try to run it.
        assertThat(prompt).contains("```vortox-actions");
        assertThat(prompt).contains("\"actions\"");
    }

    @Test
    void tellsTheModelItCannotRunCode() {
        String prompt = promptFor(request(List.of("fill"), null));

        assertThat(prompt).contains("You cannot run code");
        assertThat(prompt).contains("Never invent one");
    }

    @Test
    void warnsThatFillNeedsApproval() {
        String prompt = promptFor(request(List.of("fill"), null));

        assertThat(prompt).contains("approval");
    }

    @Test
    void feedsBackWhatHappenedToTheLastActions() {
        String prompt = promptFor(request(List.of("fill"),
                "fill #apply: failed — nothing on the page matches #apply"));

        assertThat(prompt).contains("What happened to your last page actions");
        assertThat(prompt).contains("nothing on the page matches #apply");
        // Repeating a proposal that already failed is the specific behaviour worth suppressing.
        assertThat(prompt).contains("do not simply repeat it");
    }

    @Test
    void omitsTheFeedbackSectionWhenThereIsNothingToReport() {
        assertThat(promptFor(request(List.of("fill"), null)))
                .doesNotContain("What happened to your last page actions");
        assertThat(promptFor(request(List.of("fill"), "   ")))
                .doesNotContain("What happened to your last page actions");
    }

    @Test
    void neverAdvertisesClickInThisRelease() {
        // click ships disabled: it is the one primitive that can trigger anything on the page,
        // including a delete. If it is ever added, this test should be the deliberate change.
        String prompt = promptFor(request(List.of("highlight", "scrollTo", "setClass", "setText", "fill"), null));

        assertThat(prompt).doesNotContain("- click");
    }
}
