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
 * On a pooled sidecar, "is this container linked to an agent?" has no single answer — two
 * institutions sharing one deployment can link different agents, or one can link none. The skills
 * UI reads this endpoint to decide what a tenant may see, so it has to be answered per tenant
 * rather than for the container as a whole.
 */
class AgentControllerLinkStatusTenantTest {

    private AgentController controllerWith(AnthropicKeyRefreshService refreshService) {
        AgentController controller = new AgentController(
                mock(AgentService.class), mock(SkillRegistry.class),
                mock(ChatRunService.class), mock(AgentStreamRegistry.class));
        ReflectionTestUtils.setField(controller, "anthropicKeyRefreshService", refreshService);
        return controller;
    }

    @Test
    void reportsEachTenantsOwnLinkedAgent() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig("CUBE")).thenReturn(Map.of(
                "agentId", "agent-cube", "name", "Cube Agent",
                "availableSkills", List.of("oracle_query")));
        when(refreshService.getAgentConfig("ARENA")).thenReturn(Map.of(
                "agentId", "agent-arena", "name", "Arena Agent",
                "availableSkills", List.of("file_read")));

        AgentController controller = controllerWith(refreshService);

        Map<String, Object> cube = controller.linkStatus("CUBE").getBody();
        Map<String, Object> arena = controller.linkStatus("ARENA").getBody();

        assertThat(cube).containsEntry("linked", true)
                .containsEntry("agentId", "agent-cube")
                .containsEntry("tenantCode", "CUBE")
                .containsEntry("availableSkills", List.of("oracle_query"));
        assertThat(arena).containsEntry("agentId", "agent-arena")
                .containsEntry("availableSkills", List.of("file_read"));
    }

    @Test
    void aTenantWithNoLinkedAgentIsNotGivenAnothersConfiguration() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig("CUBE")).thenReturn(Map.of("agentId", "agent-cube"));
        when(refreshService.getAgentConfig("ARENA")).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controllerWith(refreshService).linkStatus("ARENA");

        assertThat(response.getBody()).containsEntry("linked", false);
        assertThat(response.getBody()).doesNotContainKey("agentId");
    }

    @Test
    void aBlankTenantIsTreatedAsNoTenantRatherThanAsATenantNamedBlank() {
        AnthropicKeyRefreshService refreshService = mock(AnthropicKeyRefreshService.class);
        when(refreshService.getAgentConfig(null)).thenReturn(Map.of("agentId", "agent-default"));

        Map<String, Object> body = controllerWith(refreshService).linkStatus("   ").getBody();

        assertThat(body).containsEntry("agentId", "agent-default");
        assertThat(body).doesNotContainKey("tenantCode");
    }
}
