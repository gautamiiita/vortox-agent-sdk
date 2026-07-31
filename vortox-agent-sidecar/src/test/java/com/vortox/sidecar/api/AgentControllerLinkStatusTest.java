package com.vortox.sidecar.api;

import com.vortox.sidecar.service.AgentService;
import com.vortox.sidecar.service.AgentStreamRegistry;
import com.vortox.sidecar.service.AnthropicKeyRefreshService;
import com.vortox.sidecar.service.ChatRunService;
import com.vortox.sidecar.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers /agent/link-status, which the bundled skills UI uses to show which loaded skills are
 * actually reachable by the LLM (per the linked agent's availableSkills) versus just sitting in
 * the registry unused, and to switch itself read-only when a link is present.
 */
class AgentControllerLinkStatusTest {

    private AgentController controllerWithRefreshService(AnthropicKeyRefreshService refreshService) {
        AgentController controller = new AgentController(
                mock(AgentService.class), mock(SkillRegistry.class),
                mock(ChatRunService.class), mock(AgentStreamRegistry.class));
        ReflectionTestUtils.setField(controller, "anthropicKeyRefreshService", refreshService);
        return controller;
    }

    @Test
    void notLinkedWhenNoRefreshServiceBeanExists() {
        AgentController controller = controllerWithRefreshService(null);

        ResponseEntity<Map<String, Object>> response = controller.linkStatus(null);

        assertThat(response.getBody().get("linked")).isEqualTo(false);
        assertThat(response.getBody()).doesNotContainKey("availableSkills");
    }

    @Test
    void notLinkedWhenRefreshServiceHasNoAgentConfigYet() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig(null)).thenReturn(null);

        AgentController controller = controllerWithRefreshService(refreshService);
        ResponseEntity<Map<String, Object>> response = controller.linkStatus(null);

        assertThat(response.getBody().get("linked")).isEqualTo(false);
    }

    @Test
    void linkedExposesAgentIdAndAvailableSkills() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig(null)).thenReturn(Map.of(
                "agentId", "agent-123",
                "name", "TNAM Assistant",
                "availableSkills", List.of("file_read", "oracle_query")
        ));

        AgentController controller = controllerWithRefreshService(refreshService);
        ResponseEntity<Map<String, Object>> response = controller.linkStatus(null);

        Map<String, Object> body = response.getBody();
        assertThat(body.get("linked")).isEqualTo(true);
        assertThat(body.get("agentId")).isEqualTo("agent-123");
        assertThat(body.get("agentName")).isEqualTo("TNAM Assistant");
        assertThat(body.get("availableSkills")).isEqualTo(List.of("file_read", "oracle_query"));
    }

    @Test
    void linkedWithEmptyAvailableSkillsMeansAllAreAvailable() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig(null)).thenReturn(Map.of("agentId", "agent-456"));

        AgentController controller = controllerWithRefreshService(refreshService);
        ResponseEntity<Map<String, Object>> response = controller.linkStatus(null);

        assertThat(response.getBody().get("linked")).isEqualTo(true);
        assertThat((List<?>) response.getBody().get("availableSkills")).isEmpty();
    }
}
