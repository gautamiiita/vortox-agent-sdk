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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${vortox.backend.api-key:}")
    private String apiKey;

    public VortoxSkillSyncService(VortoxGateway gateway, SkillRegistry skillRegistry) {
        this.gateway = gateway;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    public void syncOnStartup() {
        sync("startup");
    }

    /** Hourly fallback — recovers from missed webhook deliveries. */
    @Scheduled(fixedDelayString = "${vortox.skill-sync.poll-interval-ms:3600000}")
    public void syncOnSchedule() {
        sync("scheduled-poll");
    }

    /** Called by the webhook controller when Vortox pushes a reload signal. */
    public void syncOnWebhook() {
        sync("webhook");
    }

    private void sync(String trigger) {
        String url = gateway.getBaseUrl() + "/api/sdk/v1/skills/sync";
        log.info("Syncing skills from Vortox [trigger={}] url={}", trigger, url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("X-Vortox-Api-Key", apiKey)
                    .header("X-Sdk-Instance-Id", gateway.getInstanceId())
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Skill sync [{}] returned HTTP {}: {}", trigger, resp.statusCode(), resp.body());
                return;
            }

            List<Map<String, Object>> skills = objectMapper.readValue(resp.body(), LIST_MAP);
            int saved = 0, failed = 0;
            for (Map<String, Object> entry : skills) {
                String name    = (String) entry.get("name");
                String content = (String) entry.get("content");
                if (name == null || content == null) continue;
                try {
                    skillRegistry.save(name, content);
                    saved++;
                } catch (Exception e) {
                    log.warn("Failed to save synced skill '{}': {}", name, e.getMessage());
                    failed++;
                }
            }
            log.info("Skill sync [{}] complete: {} saved, {} failed, {} total from Vortox",
                    trigger, saved, failed, skills.size());

        } catch (Exception e) {
            log.warn("Skill sync [{}] failed: {}", trigger, e.getMessage());
        }
    }
}
