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

    private final AtomicReference<String> cachedKey = new AtomicReference<>(null);

    private final VortoxGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${vortox.backend.api-key:}")
    private String sdkApiKey;

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
            return;
        }
        // Fallback: backend was restarted or key wasn't configured yet at registration time.
        refresh();
    }

    @Scheduled(fixedDelayString = "${vortox.anthropic-key-refresh-ms:60000}")
    public void fetchOnSchedule() {
        refresh();
    }

    /** Returns the Anthropic API key fetched from Vortox, or null if not available. */
    public String getKey() {
        return cachedKey.get();
    }

    @SuppressWarnings("unchecked")
    private void refresh() {
        String url = gateway.getBaseUrl() + "/api/sdk/v1/config";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-Vortox-Api-Key", sdkApiKey)
                    .header("X-Sdk-Instance-Id", gateway.getInstanceId())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("AnthropicKeyRefreshService: config fetch returned HTTP {}", resp.statusCode());
                return;
            }

            Map<String, Object> config = objectMapper.readValue(resp.body(), Map.class);
            String key = (String) config.get("anthropicApiKey");
            if (key != null && !key.isBlank()) {
                String previous = cachedKey.getAndSet(key);
                if (previous == null) {
                    log.info("AnthropicKeyRefreshService: Anthropic key loaded from Vortox backend");
                } else if (!previous.equals(key)) {
                    log.info("AnthropicKeyRefreshService: Anthropic key rotated (prefix: {}...)",
                            key.substring(0, Math.min(12, key.length())));
                }
            } else {
                if (cachedKey.get() != null) {
                    log.info("AnthropicKeyRefreshService: key removed from Vortox backend, clearing cache");
                    cachedKey.set(null);
                }
            }
        } catch (Exception e) {
            log.warn("AnthropicKeyRefreshService: failed to fetch config from Vortox: {}", e.getMessage());
        }
    }
}
