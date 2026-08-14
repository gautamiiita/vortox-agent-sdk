package com.vortox.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the user sees when a run ends via {@code task_complete}.
 *
 * <p>The summary is an argument to a tool call; the report the user asked for is prose the model
 * wrote while working. Returning the summary as the reply discarded that prose on every completed
 * run — an analyst agent that produced a full breakdown delivered one flat sentence instead, which
 * reads as the assistant becoming vague for no reason rather than as a bug.
 */
class ReactLoopChooseReplyTest {

    @Test
    void prefersTheModelsProseOverAShortToolSummary() {
        String report = "**17 contacts** purchased a ticket in 2025.\n\n"
                + "| Status | Orders |\n|---|---|\n| CLOSED | 17 |\n| ABANDONNED | 18 |";
        String summary = "Exported 17 contact IDs to CSV.";

        assertEquals(report, ReactLoop.chooseReply(report, summary));
    }

    /**
     * Some prompts instruct the agent to put the whole answer in the summary. Those must not be
     * regressed by this change: when the summary is the larger artefact, it is the answer.
     */
    @Test
    void keepsTheSummaryWhenItIsTheFullerAnswer() {
        String leadIn = "Let me run that query for you.";
        String summary = "17 distinct contacts purchased a ticket in 2025, excluding 18 ABANDONNED "
                + "and 1 OPEN order. Breakdown by month follows in the attached CSV.";

        assertEquals(summary, ReactLoop.chooseReply(leadIn, summary));
    }

    @Test
    void fallsBackToTheSummaryWhenTheModelWroteNoProse() {
        assertEquals("Done.", ReactLoop.chooseReply(null, "Done."));
        assertEquals("Done.", ReactLoop.chooseReply("   ", "Done."));
    }

    @Test
    void usesTheProseWhenThereIsNoSummary() {
        assertEquals("The answer is 17.", ReactLoop.chooseReply("The answer is 17.", null));
        assertEquals("The answer is 17.", ReactLoop.chooseReply("The answer is 17.", "  "));
    }

    /** Neither available: an empty reply, for the caller to substitute its own message. */
    @Test
    void returnsEmptyWhenThereIsNothingToSay() {
        assertEquals("", ReactLoop.chooseReply(null, null));
    }

    /** The summary often restates the prose's last line; repeating it back would read as a stutter. */
    @Test
    void doesNotRepeatASummaryAlreadyContainedInTheProse() {
        String prose = "Checked every order.\nExported 17 contact IDs to CSV.";
        assertEquals(prose, ReactLoop.chooseReply(prose, "Exported 17 contact IDs to CSV."));
    }
}
