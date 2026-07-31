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
        String tenantCode
) {
    /** Overload keeping the pre-tenant argument order usable for single-tenant callers and tests. */
    public AgentRunRequest(String task, List<String> skills, String systemPrompt, String model,
                           Integer maxIterations, String apiKey, String llmProvider, String llmBaseUrl) {
        this(task, skills, systemPrompt, model, maxIterations, apiKey, llmProvider, llmBaseUrl, null);
    }
}
