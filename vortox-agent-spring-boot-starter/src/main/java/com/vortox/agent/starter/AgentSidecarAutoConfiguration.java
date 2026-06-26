package com.vortox.agent.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration for the vortox-agent-sidecar client.
 *
 * Activated automatically when {@code agent.sidecar.url} is set in the application's
 * properties. Nothing else is required in the consuming application.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent.sidecar", name = "url")
@EnableConfigurationProperties(AgentSidecarProperties.class)
public class AgentSidecarAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentClient agentClient(AgentSidecarProperties props) {
        return new AgentClient(props);
    }
}
