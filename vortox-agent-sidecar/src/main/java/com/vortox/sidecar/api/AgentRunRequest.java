package com.vortox.sidecar.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentRunRequest(
        String task,
        List<String> skills,       // null or empty = all skills visible to this tenant
        String systemPrompt,       // optional override
        String model,              // optional, defaults to configured default
        Integer maxIterations,     // optional, defaults to 100
        String apiKey,             // optional, falls back to env var
        String llmProvider,        // optional: "anthropic" (default) or "local"
        String llmBaseUrl,         // optional: base URL for local LLM (e.g. https://ai.svc.elca.ch)

        /**
         * The host application's own tenant code for this run (TNAM sends its institution code).
         * Null in single-tenant deployments.
         *
         * <p>Fixed once, here at the request boundary, and passed explicitly from this point on —
         * never re-derived and never read from ambient state. {@code ChatRunService} hands runs to a
         * worker pool, so a {@code ThreadLocal} tenant would leak between tenants exactly as
         * {@code TenantContext} does on the backend's async pools.
         */
        String tenantCode,

        /**
         * Which screen of the host application this run was started from, e.g.
         * {@code tnam:order-detail}. Null when the host does not identify its surfaces.
         *
         * <p>Unlike {@code tenantCode} this cannot be established server-side: one chat endpoint
         * serves every screen, and only the browser knows which one is open. It is therefore
         * untrusted, and usable only to <em>select</em> among configurations already registered in
         * Vortox — an unrecognised value falls back to the application default. It must never grant
         * anything a run would not otherwise have.
         */
        String surface,

        /**
         * Instructions about the runtime environment — the page actions available, the host page's
         * structure — appended after the agent's own system prompt rather than replacing it.
         *
         * <p>Separate from {@code systemPrompt} because the two have different owners. The agent's
         * persona comes from Vortox and is policy; this describes what the browser can do on this
         * particular turn. Merging them, as this once did, meant a widget that sent page actions
         * silently discarded the configured persona.
         */
        String runtimeInstructions
) {
    /** Overload keeping the pre-tenant argument order usable for single-tenant callers and tests. */
    public AgentRunRequest(String task, List<String> skills, String systemPrompt, String model,
                           Integer maxIterations, String apiKey, String llmProvider, String llmBaseUrl) {
        this(task, skills, systemPrompt, model, maxIterations, apiKey, llmProvider, llmBaseUrl, null);
    }

    /** Overload for callers that name a tenant but no surface or runtime instructions. */
    public AgentRunRequest(String task, List<String> skills, String systemPrompt, String model,
                           Integer maxIterations, String apiKey, String llmProvider, String llmBaseUrl,
                           String tenantCode) {
        this(task, skills, systemPrompt, model, maxIterations, apiKey, llmProvider, llmBaseUrl,
                tenantCode, null, null);
    }
}
