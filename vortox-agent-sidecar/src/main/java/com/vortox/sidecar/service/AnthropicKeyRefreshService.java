package com.vortox.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.gateway.VortoxGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls the Vortox backend every minute for the platform-configured Anthropic API key.
 * When set in Vortox, the sidecar uses this key instead of (or as fallback to) the
 * ANTHROPIC_API_KEY env var — allowing key rotation without restarting the sidecar.
 */
@Service
@ConditionalOnProperty(name = "vortox.backend.url")
public class AnthropicKeyRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AnthropicKeyRefreshService.class);

    /**
     * The platform Anthropic key. Genuinely platform-wide, so a single value is correct — unlike the
     * agent config below, which differs per tenant.
     */
    private final AtomicReference<String> cachedKey = new AtomicReference<>(null);

    /**
     * tenantCode → that tenant's agent config (model, prompt, availableSkills, agent API key).
     *
     * <p>Was a single {@code AtomicReference}. In a pooled sidecar that means whichever tenant
     * refreshed last decides every tenant's model, system prompt and — worse — which agent API key
     * is used, so runs would silently execute under another institution's configuration. Keyed by
     * tenant, populated lazily on first sight and refreshed on the same schedule thereafter.
     */
    private final Map<String, Map<String, Object>> agentConfigByTenant = new ConcurrentHashMap<>();

    /** Tenants whose config has been fetched at least once, including those that returned none. */
    private final java.util.Set<String> knownTenants = ConcurrentHashMap.newKeySet();

    /** Key for the no-tenant bucket; a space keeps it out of the tenant-code namespace. */
    private static final String NO_TENANT = " none";

    /** Sentinel stored when a tenant genuinely has no linked agent, so absence isn't refetched forever. */
    private static final Map<String, Object> NO_AGENT = Map.of();

    private final VortoxGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${vortox.backend.api-key:}")
    private String sdkApiKey;

    /** Same warm-up hint the skill sync uses — see {@code VortoxSkillSyncService}. */
    @Value("${vortox.tenant-codes:}")
    private String tenantCodesRaw;

    /** Set once Vortox has said this key is pooled; untenanted polling then stops. */
    private volatile boolean tenantCodeRequired = false;

    private java.util.List<String> configuredTenantCodes() {
        if (tenantCodesRaw == null || tenantCodesRaw.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(tenantCodesRaw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public AnthropicKeyRefreshService(VortoxGateway gateway) {
        this.gateway = gateway;
    }

    @PostConstruct
    public void fetchOnStartup() {
        // Use the key the gateway already received at registration time — no extra HTTP call.
        String registrationKey = gateway.getRegistrationAnthropicKey();
        if (registrationKey != null && !registrationKey.isBlank()) {
            cachedKey.set(registrationKey);
            log.info("AnthropicKeyRefreshService: using key from registration response");
        }
        // Fetch full config on startup to also get agentConfig. With a pooled key an untenanted call
        // is refused, so warm up each configured code instead.
        java.util.List<String> configured = configuredTenantCodes();
        if (configured.isEmpty()) {
            refresh(null);
        } else {
            for (String tenantCode : configured) {
                refresh(tenantCode);
            }
        }
    }

    @Scheduled(fixedDelayString = "${vortox.anthropic-key-refresh-ms:60000}")
    public void fetchOnSchedule() {
        // Skipped once the key is known to be pooled — this polls every 60s, so repeating a call the
        // backend has already refused is the loudest possible way to log a configuration mistake.
        if (!tenantCodeRequired) {
            refresh(null);
        }
        // Refresh configured codes plus every tenant seen in traffic. An agent's model or key can
        // change in Vortox, and a tenant fetched once and never again keeps the old configuration.
        java.util.Set<String> toRefresh = new java.util.LinkedHashSet<>(configuredTenantCodes());
        toRefresh.addAll(knownTenants);
        for (String tenantCode : toRefresh) {
            refresh(tenantCode);
        }
    }

    /** Returns the Anthropic API key fetched from Vortox, or null if not available. */
    public String getKey() {
        return cachedKey.get();
    }

    /** Config for a deployment with no tenant dimension. */
    public Map<String, Object> getAgentConfig() {
        return getAgentConfig(null);
    }

    /**
     * The agent config for one tenant, or null if that tenant has no linked agent.
     *
     * <p>Fetched on first sight of a tenant, so the sidecar learns its tenants from traffic rather
     * than enumerating them. Never falls back to another tenant's config or to the no-tenant
     * bucket: running under the wrong institution's agent would mean the wrong model, the wrong
     * system prompt and the wrong API key, all silently.
     */
    public Map<String, Object> getAgentConfig(String tenantCode) {
        String key = key(tenantCode);
        Map<String, Object> cached = agentConfigByTenant.get(key);
        if (cached == null && tenantCode != null && !tenantCode.isBlank()) {
            refresh(tenantCode);
            cached = agentConfigByTenant.get(key);
        }
        return cached == NO_AGENT ? null : cached;
    }

    @SuppressWarnings("unchecked")
    private void refresh(String tenantCode) {
        String url = gateway.getBaseUrl() + "/api/sdk/v1/config";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vortox-Api-Key", sdkApiKey)
                    .header("X-Sdk-Instance-Id", gateway.getInstanceId());
            if (tenantCode != null && !tenantCode.isBlank()) {
                builder.header(com.vortox.agent.gateway.VortoxGateway.TENANT_CODE_HEADER, tenantCode);
            }
            HttpRequest req = builder
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                if (resp.statusCode() == 403 && resp.body() != null
                        && resp.body().contains("TENANT_CODE_REQUIRED")) {
                    if (!tenantCodeRequired) {
                        log.warn("AnthropicKeyRefreshService: SDK key is pooled — Vortox requires a "
                                + "tenant code. Untenanted config polling will stop.");
                    }
                    tenantCodeRequired = true;
                    return;
                }
                log.warn("AnthropicKeyRefreshService: config fetch for tenant={} returned HTTP {}",
                        tenantCode, resp.statusCode());
                // A refused tenant must not keep whatever it had cached — its access may have been
                // disabled in Vortox, and continuing on a stale config would ignore that.
                if (tenantCode != null && !tenantCode.isBlank()) {
                    agentConfigByTenant.remove(key(tenantCode));
                }
                return;
            }

            Map<String, Object> config = objectMapper.readValue(resp.body(), Map.class);

            // The platform key is not tenant-scoped; only the untenanted refresh maintains it, so a
            // per-tenant fetch can't clear it as a side effect.
            if (tenantCode == null || tenantCode.isBlank()) {
                String key = (String) config.get("anthropicApiKey");
                if (key != null && !key.isBlank()) {
                    String previous = cachedKey.getAndSet(key);
                    if (previous == null) {
                        log.info("AnthropicKeyRefreshService: Anthropic key loaded from Vortox backend");
                    } else if (!previous.equals(key)) {
                        log.info("AnthropicKeyRefreshService: Anthropic key rotated (prefix: {}...)",
                                key.substring(0, Math.min(12, key.length())));
                    }
                } else if (cachedKey.get() != null) {
                    log.info("AnthropicKeyRefreshService: key removed from Vortox backend, clearing cache");
                    cachedKey.set(null);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> agentConfig = (Map<String, Object>) config.get("agentConfig");
            String bucket = key(tenantCode);
            Map<String, Object> previous = agentConfigByTenant.put(
                    bucket, agentConfig == null ? NO_AGENT : agentConfig);
            if (tenantCode != null && !tenantCode.isBlank()) knownTenants.add(tenantCode);

            if (agentConfig != null && (previous == null || previous == NO_AGENT)) {
                log.info("AnthropicKeyRefreshService: agent config loaded for tenant={} — agentId='{}' model={}",
                        tenantCode, agentConfig.get("agentId"), agentConfig.get("model"));
            } else if (agentConfig == null && previous != null && previous != NO_AGENT) {
                log.info("AnthropicKeyRefreshService: agent config removed for tenant={}", tenantCode);
            }
        } catch (Exception e) {
            log.warn("AnthropicKeyRefreshService: failed to fetch config for tenant={}: {}",
                    tenantCode, e.getMessage());
        }
    }

    private static String key(String tenantCode) {
        return tenantCode == null || tenantCode.isBlank() ? NO_TENANT : tenantCode;
    }
}
