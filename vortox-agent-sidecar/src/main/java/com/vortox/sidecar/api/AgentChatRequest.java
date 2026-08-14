package com.vortox.sidecar.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Widget-facing chat request format.
 * Sent by vortox-agent-widget.js on every user message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentChatRequest(
        String message,
        List<Map<String, Object>> history,
        Map<String, Object> context,
        Boolean allowPageScripts,
        String pageApiDescription,

        /**
         * Names of the DOM operations this widget will execute, sent by the widget itself since it
         * owns the implementations. Advisory to the prompt only: the browser validates every
         * requested action against its own definitions before running anything, so a name that
         * arrives here but is not implemented there is simply refused.
         */
        List<String> pageActions,

        /**
         * What became of the actions proposed on the previous turn — applied, failed, or dismissed
         * by the operator.
         *
         * <p>Actions execute after the run has finished, so without this the agent proposes into a
         * void: a stale selector or a refusal would never reach it, and it would keep repeating a
         * suggestion that has already failed twice.
         */
        String lastActionResults,
        String systemPrompt,
        String model,
        String llmProvider,        // optional: "anthropic" (default) or "local"
        String llmBaseUrl,         // optional: base URL for local LLM
        String llmApiKey,          // optional: API key/token for the LLM (overrides env default)

        /**
         * The host application's own tenant code, set server-side by its chat proxy — TNAM's
         * enricher already resolves the institution code from the authenticated session.
         *
         * <p>A structured field rather than another entry in {@code context}, because {@code context}
         * is inlined into the prompt as free text and is partly browser-supplied. This value decides
         * which skills and which secrets a run gets, so it must be something the server sets and the
         * page cannot.
         */
        String tenantCode,

        /**
         * Which screen the widget is embedded in, e.g. {@code tnam:order-detail}. Sent by the widget
         * from its own configuration, since only the browser knows which screen is open — one chat
         * endpoint serves them all.
         *
         * <p>Consequently untrusted, unlike {@code tenantCode}: it selects among configurations
         * already registered in Vortox for this application, and an unrecognised value falls back to
         * the application default. A host application that can determine the surface server-side is
         * free to overwrite it in its own {@code ChatContextEnricher}.
         */
        String surface
) {}
