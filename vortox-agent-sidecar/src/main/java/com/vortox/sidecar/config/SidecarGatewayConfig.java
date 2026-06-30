package com.vortox.sidecar.config;

import com.vortox.agent.gateway.GatewayMemoryStore;
import com.vortox.agent.gateway.GatewayToolExecutor;
import com.vortox.agent.gateway.VortoxGateway;
import com.vortox.sidecar.skill.ScriptToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Optional Vortox gateway wiring for the sidecar.
 *
 * When {@code vortox.backend.url} is set, the sidecar connects to the Vortox
 * control plane: it registers itself on startup, persists memories to Vortox DB,
 * and can propose skills or send notifications through the gateway.
 */
@Configuration
@ConditionalOnProperty(name = "vortox.backend.url")
public class SidecarGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(SidecarGatewayConfig.class);

    @Bean
    public VortoxGateway vortoxGateway(
            @Value("${vortox.backend.url}") String backendUrl,
            @Value("${vortox.backend.api-key:}") String apiKey,
            @Value("${vortox.sdk.instance-id:}") String instanceIdProp,
            @Value("${vortox.sdk.application-name:vortox-agent-sidecar}") String applicationName,
            @Value("${vortox.sdk.version:1.0.0}") String version,
            @Value("${vortox.sdk.callback-url:}") String callbackUrl
    ) {
        String instanceId = instanceIdProp.isBlank() ? UUID.randomUUID().toString() : instanceIdProp;
        String resolvedCallbackUrl = callbackUrl.isBlank() ? null : callbackUrl;
        log.info("Connecting sidecar to Vortox backend at {} (instance={}, callbackUrl={})",
                backendUrl, instanceId, resolvedCallbackUrl != null ? resolvedCallbackUrl : "none");
        VortoxGateway gateway = new VortoxGateway(backendUrl, apiKey, instanceId);
        gateway.registerInstance(applicationName, version, resolvedCallbackUrl);
        return gateway;
    }

    @Bean
    public GatewayMemoryStore gatewayMemoryStore(
            VortoxGateway gateway,
            @Value("${vortox.sdk.agent-id:sidecar-agent}") String agentId
    ) {
        return new GatewayMemoryStore(gateway, agentId);
    }

    @Bean
    public GatewayToolExecutor gatewayToolExecutor(VortoxGateway gateway, ScriptToolExecutor scriptExecutor) {
        return new GatewayToolExecutor(gateway, scriptExecutor);
    }
}
