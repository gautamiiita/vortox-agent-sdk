package com.vortox.agent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vortox.agent.spi.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * ToolExecutor that intercepts Vortox control-plane tool calls and routes them
 * to the Vortox backend via {@link VortoxGateway}.
 *
 * All other tool names are forwarded to the delegate executor (e.g. ScriptToolExecutor
 * in the sidecar, or a user-supplied executor in an embedded application).
 */
public class GatewayToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(GatewayToolExecutor.class);

    private static final Set<String> GATEWAY_TOOLS = Set.of(
            "propose_new_skill",
            "send_notification"
    );

    private final VortoxGateway gateway;
    private final ToolExecutor delegate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param gateway  Vortox control-plane client
     * @param delegate fallback executor for skill tools; may be {@code null} if no local
     *                 skill execution is needed (gateway-only mode)
     */
    public GatewayToolExecutor(VortoxGateway gateway, ToolExecutor delegate) {
        this.gateway = gateway;
        this.delegate = delegate;
    }

    @Override
    public String execute(String toolName, Map<String, Object> params, String runId) {
        if (GATEWAY_TOOLS.contains(toolName)) {
            log.debug("Routing tool '{}' to Vortox gateway", toolName);
            return executeGatewayTool(toolName, params, runId);
        }
        if (delegate != null) {
            return delegate.execute(toolName, params, runId);
        }
        throw new ToolExecutionException("Tool not available: " + toolName
                + " (no delegate executor configured and not a gateway tool)");
    }

    private String executeGatewayTool(String toolName, Map<String, Object> params, String runId) {
        try {
            return switch (toolName) {
                case "propose_new_skill" -> {
                    String agentId = (String) params.getOrDefault("agentId", runId);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> skillDef = params.containsKey("skill")
                            ? (Map<String, Object>) params.get("skill")
                            : params;
                    String result = gateway.proposeSkill(agentId, skillDef);
                    yield toJson(Map.of("result", result));
                }
                case "send_notification" -> {
                    String channel = (String) params.getOrDefault("channel", "default");
                    String target = (String) params.getOrDefault("target", "");
                    String subject = (String) params.getOrDefault("subject", "Agent Notification");
                    String message = (String) params.getOrDefault("message", "");
                    String result = gateway.sendNotification(channel, target, subject, message);
                    yield toJson(Map.of("result", result));
                }
                default -> throw new ToolExecutionException("Unknown gateway tool: " + toolName);
            };
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("Gateway tool execution failed for '" + toolName + "': " + e.getMessage(), e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"result\":\"ok\"}";
        }
    }
}
