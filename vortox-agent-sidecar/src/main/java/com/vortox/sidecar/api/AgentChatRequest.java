package com.vortox.sidecar.api;

import java.util.List;
import java.util.Map;

/**
 * Widget-facing chat request format.
 * Sent by vortox-agent-widget.js on every user message.
 */
public record AgentChatRequest(
        String message,
        List<Map<String, Object>> history,
        Map<String, Object> context,
        Boolean allowPageScripts,
        String pageApiDescription,
        String systemPrompt,
        String model
) {}
