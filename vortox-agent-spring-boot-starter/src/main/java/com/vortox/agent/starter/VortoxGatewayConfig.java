package com.vortox.agent.starter;

import com.vortox.agent.gateway.GatewayMemoryStore;
import com.vortox.agent.gateway.GatewayToolExecutor;
import com.vortox.agent.gateway.VortoxGateway;
import com.vortox.agent.spi.MemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

/**
 * Auto-configuration that wires the Vortox gateway into a Spring application.
 *
 * Activated when {@code vortox.backend.url} is set in application properties.
 * Provides:
 * <ul>
 *   <li>{@link VortoxGateway} — HTTP client to the Vortox control plane</li>
 *   <li>{@link GatewayMemoryStore} — persists memories to Vortox DB</li>
 *   <li>{@link GatewayToolExecutor} — routes control-plane tool calls to Vortox</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "vortox.backend.url")
public class VortoxGatewayConfig {

    @Bean
    @ConditionalOnMissingBean
    public VortoxGateway vortoxGateway(
            @Value("${vortox.backend.url}") String backendUrl,
            @Value("${vortox.backend.api-key:}") String apiKey,
            @Value("${vortox.sdk.instance-id:}") String instanceIdProp,
            @Value("${vortox.sdk.application-name:vortox-sdk-app}") String applicationName,
            @Value("${vortox.sdk.version:0.0.0}") String version
    ) {
        String instanceId = instanceIdProp.isBlank() ? UUID.randomUUID().toString() : instanceIdProp;
        VortoxGateway gateway = new VortoxGateway(backendUrl, apiKey, instanceId);
        gateway.registerInstance(applicationName, version);
        return gateway;
    }

    @Bean
    @ConditionalOnMissingBean
    public GatewayMemoryStore gatewayMemoryStore(
            VortoxGateway gateway,
            @Value("${vortox.sdk.agent-id:sdk-agent}") String agentId
    ) {
        return new GatewayMemoryStore(gateway, agentId);
    }

    @Bean
    @ConditionalOnMissingBean(MemoryStore.class)
    public MemoryStore memoryStore(GatewayMemoryStore gatewayMemoryStore) {
        return gatewayMemoryStore;
    }
}
