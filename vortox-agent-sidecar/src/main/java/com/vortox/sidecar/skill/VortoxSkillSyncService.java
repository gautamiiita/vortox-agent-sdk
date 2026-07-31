package com.vortox.sidecar.skill;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Map;

/**
 * Pulls approved skill definitions from the Vortox control plane and writes them to disk.
 *
 * Primary trigger: Vortox pushes a POST /vortox/skills/reload when any skill is
 * approved, updated, or deleted. This service is also called on startup and runs
 * an hourly fallback poll to recover from missed webhook deliveries.
 */
@Service
@ConditionalOnProperty(name = "vortox.backend.url")
public class VortoxSkillSyncService {

    private static final Logger log = LoggerFactory.getLogger(VortoxSkillSyncService.class);
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP = new TypeReference<>() {};

    private final VortoxGateway gateway;
    private final SkillRegistry skillRegistry;
    private final SkillEnvStore skillEnvStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${vortox.backend.api-key:}")
    private String apiKey;

    /**
     * Tenant codes to warm up at startup, comma-separated — {@code VORTOX_TENANT_CODES=CUBE,ARENA}.
     *
     * <p>A pooled key refuses an untenanted request, so without this a pooled sidecar starts with no
     * skills at all and every scheduled poll is a 403. Per-request codes populate tenants lazily, but
     * nothing supplies one before the first chat arrives.
     *
     * <p>This is a warm-up hint, not the authority: which tenant a <em>run</em> acts for still comes
     * from that request. Leaving it empty is fine for a tenant-owned key, and acceptable for a pooled
     * one if you can tolerate the first request per tenant paying for the sync.
     */
    @Value("${vortox.tenant-codes:}")
    private String tenantCodesRaw;

    /**
     * Set once the backend has told us this key is pooled and needs a tenant code. Untenanted syncs
     * are pointless from then on, so they stop rather than logging a 403 every hour.
     */
    private volatile boolean tenantCodeRequired = false;

    public VortoxSkillSyncService(VortoxGateway gateway, SkillRegistry skillRegistry,
                                   SkillEnvStore skillEnvStore) {
        this.gateway = gateway;
        this.skillRegistry = skillRegistry;
        this.skillEnvStore = skillEnvStore;
    }

    /** Tenants synced at least once, so a repeat request doesn't refetch on every message. */
    private final java.util.Set<String> syncedTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Configured warm-up codes, in order, with blanks dropped. */
    private List<String> configuredTenantCodes() {
        if (tenantCodesRaw == null || tenantCodesRaw.isBlank()) return List.of();
        return java.util.Arrays.stream(tenantCodesRaw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Loads this key's configuration.
     *
     * <p>Configuration belongs to the key: one key, one tenant, one set of skills and secrets. The
     * customer code on a request says <em>which customer</em> is calling — for attribution and the
     * per-customer switch — not which configuration to use, so nothing here needs a code.
     *
     * <p>{@code VORTOX_TENANT_CODES} remains only for a platform-level key that spans tenants, where
     * the code genuinely selects the configuration and an untenanted fetch is refused.
     */
    @PostConstruct
    public void syncOnStartup() {
        sync("startup", null);

        List<String> configured = configuredTenantCodes();
        if (!configured.isEmpty()) {
            log.info("Also warming up {} configured code(s) — only needed for a platform-level key: {}",
                    configured.size(), configured);
            for (String tenantCode : configured) {
                sync("startup", tenantCode);
            }
        } else if (tenantCodeRequired) {
            log.warn("This key is platform-level, so its configuration cannot be fetched without a "
                    + "customer code. Set VORTOX_TENANT_CODES, or let the first request per customer "
                    + "load it lazily.");
        }
    }

    /** Hourly fallback — recovers from missed webhook deliveries. */
    @Scheduled(fixedDelayString = "${vortox.skill-sync.poll-interval-ms:3600000}")
    public void syncOnSchedule() {
        // Skipped once the key is known to be pooled: repeating a request the backend has already
        // told us it will refuse only fills the log.
        if (!tenantCodeRequired) {
            sync("scheduled-poll", null);
        }
        // Refresh configured codes plus every tenant seen in traffic. Secrets rotate, so a tenant tier
        // that is never refetched would keep serving stale credentials until the container restarts.
        java.util.Set<String> toRefresh = new java.util.LinkedHashSet<>(configuredTenantCodes());
        toRefresh.addAll(syncedTenants);
        for (String tenantCode : toRefresh) {
            sync("scheduled-poll", tenantCode);
        }
    }

    /** Called by the webhook controller when Vortox pushes a reload signal. */
    public void syncOnWebhook() {
        syncOnWebhook(null);
    }

    /**
     * Reload signal for one tenant, or for the shared tier when {@code tenantCode} is null. Scoped
     * so one tenant's skill change doesn't force every other tenant's tier to be refetched.
     */
    public void syncOnWebhook(String tenantCode) {
        sync("webhook", tenantCode);
        if (tenantCode == null) {
            for (String known : java.util.Set.copyOf(syncedTenants)) {
                sync("webhook", known);
            }
        }
    }

    /**
     * Fetches a tenant's tier the first time it is seen. Called at the start of a run so a tenant's
     * private skills and secrets are present without the sidecar having to know every tenant up
     * front — it learns them from traffic.
     */
    public void ensureTenantSynced(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) return;
        if (syncedTenants.contains(tenantCode)) return;
        sync("first-use", tenantCode);
    }

    private void sync(String trigger, String tenantCode) {
        String url = gateway.getBaseUrl() + "/api/sdk/v1/skills/sync";
        log.info("Syncing skills from Vortox [trigger={} tenant={}] url={}", trigger, tenantCode, url);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Vortox-Api-Key", apiKey)
                    .header("X-Sdk-Instance-Id", gateway.getInstanceId());
            if (tenantCode != null && !tenantCode.isBlank()) {
                builder.header(VortoxGateway.TENANT_CODE_HEADER, tenantCode);
            }
            HttpRequest req = builder.GET().timeout(Duration.ofSeconds(30)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                // A pooled key refusing an untenanted call is a configuration fact, not a transient
                // failure — record it so the poll stops repeating a request that cannot succeed.
                if (resp.statusCode() == 403 && resp.body() != null
                        && resp.body().contains("TENANT_CODE_REQUIRED")) {
                    if (!tenantCodeRequired) {
                        log.warn("SDK key is pooled: Vortox requires a tenant code. Untenanted syncs "
                                + "will stop. Configure VORTOX_TENANT_CODES, or let per-request codes "
                                + "populate tenants lazily.");
                    }
                    tenantCodeRequired = true;
                    return;
                }
                log.warn("Skill sync [{} tenant={}] returned HTTP {}: {}",
                        trigger, tenantCode, resp.statusCode(), resp.body());
                return;
            }

            List<Map<String, Object>> skills = objectMapper.readValue(resp.body(), LIST_MAP);
            int sharedSaved = 0, privateSaved = 0, failed = 0;
            Map<String, String> tenantBodies = new java.util.LinkedHashMap<>();
            Map<String, Map<String, String>> newEnvVars = new java.util.HashMap<>();

            for (Map<String, Object> entry : skills) {
                String name    = (String) entry.get("name");
                String content = (String) entry.get("content");
                if (name == null || content == null) continue;

                // Absent `shared` means an older backend that doesn't tier its response. Treating
                // that as shared preserves the previous single-tier behaviour rather than silently
                // filing everything under one tenant.
                boolean shared = !(entry.get("shared") instanceof Boolean b) || b;

                if (shared) {
                    try {
                        skillRegistry.saveFromVortox(null, name, content);
                        sharedSaved++;
                    } catch (Exception e) {
                        log.warn("Failed to save shared skill '{}': {}", name, e.getMessage());
                        failed++;
                        continue;
                    }
                } else if (tenantCode != null) {
                    tenantBodies.put(name, content);
                    privateSaved++;
                } else {
                    // A tenant-private skill arriving on an untenanted sync has nowhere safe to go.
                    log.warn("Ignoring tenant-private skill '{}' returned by an untenanted sync", name);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, String> envVars = (Map<String, String>) entry.get("envVars");
                if (envVars != null && !envVars.isEmpty()) {
                    newEnvVars.put(name, envVars);
                }
            }

            if (tenantCode != null) {
                // Replaces this tenant's tier only, so skills it lost are evicted while no other
                // tenant's are touched.
                skillRegistry.replaceTenantTier(tenantCode, tenantBodies);
            }

            // Replace this tenant's env values so revoked secrets stop being injected and rotated
            // ones are picked up, without a container restart and without clearing anyone else.
            skillEnvStore.resetTenant(tenantCode, newEnvVars);
            if (tenantCode != null) syncedTenants.add(tenantCode);

            log.info("Skill sync [{} tenant={}] complete: {} shared, {} private, {} failed, "
                            + "{} total, {} with env vars",
                    trigger, tenantCode, sharedSaved, privateSaved, failed, skills.size(), newEnvVars.size());

        } catch (Exception e) {
            log.warn("Skill sync [{} tenant={}] failed: {}", trigger, tenantCode, e.getMessage());
        }
    }
}
