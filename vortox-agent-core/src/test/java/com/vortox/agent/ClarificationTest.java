package com.vortox.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The layout the dashboard parses into a question, option buttons and details (2026-10-18). */
class ClarificationTest {

    @Test
    @DisplayName("question, lettered options and details, in that order")
    void layout() {
        String text = Clarification.text(Map.of(
                "question", " Which do you want? ",
                "options", List.of("Accept the run as evidence", "Re-run with\napp_preview", " "),
                "context", "## Why\nNo stack exists."), "fallback");
        assertEquals("""
                Which do you want?

                Options:
                (a) Accept the run as evidence
                (b) Re-run with app_preview

                Details:
                ## Why
                No stack exists.""", text);
    }

    @Test
    @DisplayName("a plain question stays exactly as it was asked")
    void plainQuestion() {
        assertEquals("Which database?", Clarification.text(Map.of("question", "Which database?"), "x"));
        assertEquals("More information needed.", Clarification.text(Map.of(), "More information needed."));
    }

    @Test
    @DisplayName("the schema keeps question required and offers options and context")
    void schema() {
        Map<String, Object> schema = Clarification.inputSchema();
        assertEquals(List.of("question"), schema.get("required"));
        assertTrue(((Map<?, ?>) schema.get("properties")).keySet().containsAll(List.of("question", "options", "context")));
    }
}
