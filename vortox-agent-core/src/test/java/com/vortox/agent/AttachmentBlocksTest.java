package com.vortox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A screenshot attached to a task reaches the model. The first user message was always a plain
 * string, so the image blocks the backend built for a task's attachments were dropped before the
 * loop ever started; and the OpenAI-compatible client flattened any block list to its text.
 */
class AttachmentBlocksTest {

    private static final Map<String, Object> IMAGE = Map.of("type", "image", "source",
            Map.of("type", "base64", "media_type", "image/png", "data", "iVBORw0KGgo="));

    @Test
    void noAttachmentsKeepsThePlainString() {
        assertSame("Fix it", ReactLoop.firstUserContent("Fix it", null));
        assertSame("Fix it", ReactLoop.firstUserContent("Fix it", List.of()));
    }

    @Test
    void attachmentsComeFirstThenTheText() {
        Object content = ReactLoop.firstUserContent("Fix the layout", List.of(IMAGE));

        assertEquals(List.of(IMAGE, Map.of("type", "text", "text", "Fix the layout")), content);
    }

    @Test
    void openAiClientSendsTheImageAsADataUrl() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), 1024, "http://x");

        List<Map<String, Object>> out = client.translateMessage(Map.of("role", "user", "content",
                List.of(IMAGE, Map.of("type", "text", "text", "Fix the layout"))));

        assertEquals(1, out.size());
        assertEquals(List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,iVBORw0KGgo=")),
                Map.of("type", "text", "text", "Fix the layout")), out.get(0).get("content"));
    }

    @Test
    void openAiClientStillFlattensTextOnlyBlocks() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(new ObjectMapper(), 1024, "http://x");

        List<Map<String, Object>> out = client.translateMessage(Map.of("role", "user", "content",
                List.of(Map.of("type", "text", "text", "a"), Map.of("type", "text", "text", "b"))));

        assertEquals("ab", out.get(0).get("content"));
    }
}
