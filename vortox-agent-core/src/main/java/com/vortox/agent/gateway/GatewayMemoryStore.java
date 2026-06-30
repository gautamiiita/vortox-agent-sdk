package com.vortox.agent.gateway;

import com.vortox.agent.spi.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MemoryStore implementation that persists agent memories to the Vortox backend
 * via {@link VortoxGateway}.
 *
 * Replaces the default {@link com.vortox.agent.spi.InMemoryStore} when the SDK is
 * connected to Vortox, giving the agent persistent cross-run memory that can be
 * viewed and managed from the Vortox control panel.
 */
public class GatewayMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(GatewayMemoryStore.class);

    private final VortoxGateway gateway;
    private final String defaultAgentId;

    /**
     * @param gateway        Vortox control-plane client
     * @param defaultAgentId agent identifier used when scope=task (task-scoped memories
     *                       are stored under this agent's namespace in the backend)
     */
    public GatewayMemoryStore(VortoxGateway gateway, String defaultAgentId) {
        this.gateway = gateway;
        this.defaultAgentId = defaultAgentId != null ? defaultAgentId : "sdk-agent";
    }

    @Override
    public void store(String scope, String scopeId, String key, String content, String type, int importance) {
        String effectiveAgentId = "agent".equals(scope) ? scopeId : defaultAgentId;
        gateway.storeMemory(effectiveAgentId, key, content, type, importance);
    }

    @Override
    public List<MemoryEntry> recallForAgent(String agentId, String query) {
        List<Map<String, Object>> memories = gateway.recallMemory(agentId, query);
        return toEntries(memories);
    }

    @Override
    public List<MemoryEntry> loadForRun(String runId) {
        List<Map<String, Object>> memories = gateway.recallMemory(defaultAgentId, null);
        return toEntries(memories);
    }

    private List<MemoryEntry> toEntries(List<Map<String, Object>> memories) {
        return memories.stream()
                .map(m -> new MemoryEntry(
                        (String) m.getOrDefault("key", ""),
                        (String) m.getOrDefault("content", ""),
                        (String) m.getOrDefault("type", "CONTEXT"),
                        m.containsKey("importance") ? ((Number) m.get("importance")).intValue() : 3
                ))
                .collect(Collectors.toList());
    }
}
