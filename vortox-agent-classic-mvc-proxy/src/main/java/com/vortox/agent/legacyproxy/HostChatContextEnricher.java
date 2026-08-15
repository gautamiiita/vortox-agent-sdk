package com.vortox.agent.legacyproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.Supplier;

/**
 * The enricher every host needs, so no host has to write it again.
 *
 * <p>An integrating application has exactly two things the SDK cannot know: who is signed in, and
 * which customer they are acting for. Everything else a host was doing in its own enricher —
 * discarding fields the browser must not control, deciding the model, sanitising the screen id,
 * withholding page structure — is identical wherever it is written, and was being reimplemented per
 * host. That is not just duplication: the first host to write it hardcoded a model constant, which
 * silently overrode the model configured in Vortox for every request it relayed, and nobody
 * noticed until two sidecar log lines were read side by side. A shared implementation makes that
 * class of mistake impossible to introduce in a host at all.
 *
 * <p>Supply identity and the rest is handled:
 *
 * <pre>{@code
 * HostChatContextEnricher.builder()
 *     .application("TNAM")
 *     .user(()   -> ContextProviderFactory.getContextProvider().getCurrentUserName())
 *     .tenant(() -> ContextProviderFactory.getContextProvider().getCurrentInstitutionCode())
 *     .build();
 * }</pre>
 *
 * <p><strong>What this refuses to trust.</strong> The endpoint sits behind the host's session, but
 * "authenticated" is not "authorised to redefine the assistant". A signed-in user must not be able
 * to choose the model, replace the agent's instructions, point the run at another LLM, enable
 * script execution, or — most importantly — name a different customer and receive that customer's
 * credentials. Each of those arrives as an ordinary JSON field and each is removed here.
 */
public final class HostChatContextEnricher implements ChatContextEnricher {

    /** Screen ids are compared and logged, so they are held to an identifier charset. */
    private static final int SURFACE_MAX = 64;

    private final String application;
    private final Supplier<String> userSupplier;
    private final Supplier<String> tenantSupplier;
    private final String modelOverride;
    private final boolean allowPageScripts;

    private HostChatContextEnricher(Builder b) {
        this.application      = b.application;
        this.userSupplier     = b.userSupplier;
        this.tenantSupplier   = b.tenantSupplier;
        this.modelOverride    = b.modelOverride;
        this.allowPageScripts = b.allowPageScripts;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void enrich(ObjectNode payload, RelayRequestContext context) {
        JsonNode existing = payload.get("context");
        ObjectNode ctx = existing != null && existing.isObject()
                ? (ObjectNode) existing
                : payload.putObject("context");

        String user   = call(userSupplier);
        String tenant = call(tenantSupplier);

        if (user != null)        ctx.put("authenticatedUser", user);
        if (tenant != null)      ctx.put("institutionCode", tenant);
        if (application != null) ctx.put("application", application);

        // ── Fields the browser must never decide ──────────────────────────────────────────
        payload.remove("llmProvider");
        payload.remove("llmBaseUrl");
        payload.remove("llmApiKey");

        // The agent's persona belongs to its configuration in Vortox, not to whatever posts here.
        payload.remove("systemPrompt");

        // Discarding a client-supplied model and choosing one for it are different operations, and
        // only the first is a security control. Absent by default so the agent's own configured
        // model stands; a host imposes one only when it means to.
        payload.remove("model");
        if (isSet(modelOverride)) {
            payload.put("model", modelOverride.trim());
        }

        // Model-authored JavaScript executed in the operator's session. Off unless a host opts in,
        // and page actions supersede it: they are a closed set the browser validates.
        payload.put("allowPageScripts", allowPageScripts);

        // Page structure is collected by the page, so this cannot grant the capability — but it can
        // withdraw it, which is the half that matters when a deployment decides no page data may
        // reach the LLM.
        if (!pageContextEnabled()) {
            payload.remove("pageApiDescription");
        }

        sanitiseSurface(payload);

        // ── The tenant, as a first-class field ────────────────────────────────────────────
        //
        // The copy in `context` above is inlined into the prompt as free text and merges with a map
        // the browser contributes to, so it can only ever be advisory. This one decides which
        // customer's skills a run can see and whose secrets are injected into them, so it is set
        // from the session and any value the client sent is discarded rather than merged with.
        payload.remove("tenantCode");
        if (tenant != null) {
            payload.put("tenantCode", tenant);
        }
        // Left absent when there is no tenant: the sidecar then runs on shared skills with no
        // tenant secrets, which is the safe degradation. A blank would be looked up and refused.
    }

    /**
     * The screen id names which configuration to select, and is chosen by the page — so it is
     * cleaned rather than trusted. It can only ever pick among configurations already registered in
     * Vortox; an unrecognised value falls back to the application default, and one with nothing
     * usable left is dropped so that "absent" means exactly that.
     */
    private static void sanitiseSurface(ObjectNode payload) {
        JsonNode surface = payload.get("surface");
        if (surface == null || !surface.isTextual()) return;

        String cleaned = surface.asText().toLowerCase().replaceAll("[^a-z0-9:_.-]", "").trim();
        // A bare prefix such as "tnam:" identifies no screen.
        if (cleaned.isEmpty() || cleaned.endsWith(":")) {
            payload.remove("surface");
            return;
        }
        payload.put("surface", cleaned.length() > SURFACE_MAX ? cleaned.substring(0, SURFACE_MAX) : cleaned);
    }

    /**
     * Whether page structure may reach the LLM at all.
     *
     * <p>Permitted by default, because nothing is collected unless a page opts in first. This is the
     * switch that lets an operator overrule that for a whole deployment — without editing pages or
     * waiting for a release.
     */
    static boolean pageContextEnabled() {
        String flag = System.getProperty("agent.page-context.enabled");
        if (flag == null) {
            flag = System.getenv("AGENT_PAGE_CONTEXT_ENABLED");
        }
        return flag == null || !"false".equalsIgnoreCase(flag.trim());
    }

    /** A supplier reading from a request-scoped context can throw off-request; that is not fatal here. */
    private static String call(Supplier<String> supplier) {
        if (supplier == null) return null;
        try {
            String value = supplier.get();
            return isSet(value) ? value.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isSet(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public static final class Builder {
        private String application;
        private Supplier<String> userSupplier;
        private Supplier<String> tenantSupplier;
        private String modelOverride;
        private boolean allowPageScripts = false;

        /** Names the host in the prompt context, e.g. {@code TNAM}. */
        public Builder application(String application) {
            this.application = application;
            return this;
        }

        /** Who is signed in. Read per request, from the host's own session. */
        public Builder user(Supplier<String> userSupplier) {
            this.userSupplier = userSupplier;
            return this;
        }

        /** Which customer they are acting for — this selects the skills and secrets a run receives. */
        public Builder tenant(Supplier<String> tenantSupplier) {
            this.tenantSupplier = tenantSupplier;
            return this;
        }

        /**
         * Pins every run from this host to one model, overriding the agent's own configuration.
         * Leave unset — the normal case — and Vortox decides, which is where the model can be
         * changed without a redeploy.
         */
        public Builder model(String modelOverride) {
            this.modelOverride = modelOverride;
            return this;
        }

        /**
         * Permits model-authored JavaScript to run in the operator's session. Almost certainly not
         * what you want: page actions provide the same capability as a closed set the browser
         * validates, and cannot call {@code fetch}, read cookies or evaluate a string.
         */
        public Builder allowPageScripts(boolean allowPageScripts) {
            this.allowPageScripts = allowPageScripts;
            return this;
        }

        public HostChatContextEnricher build() {
            return new HostChatContextEnricher(this);
        }
    }
}
