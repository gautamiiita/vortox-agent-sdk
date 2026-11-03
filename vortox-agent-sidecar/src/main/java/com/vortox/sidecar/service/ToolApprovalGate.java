package com.vortox.sidecar.service;

import com.vortox.agent.spi.ToolGate;
import com.vortox.sidecar.skill.SkillDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@link ToolGate} a sidecar run uses: what the run's tools DECLARE ({@code requires_approval}
 * in their definition — a SKILL.md, the skills screen, or a tool configured in Vortox and synced
 * down), then any gates the host application supplies as beans for tools it builds in code.
 *
 * <p>The text a person reads is the declared one, filled in from this call's own arguments: approving
 * "a declaration" is not approving the one about to be made. Values are flattened to one line and cut,
 * because they come from the model and are shown in a card, not a log.
 *
 * <p>Control tools are never held whatever a gate answers: holding {@code task_complete} leaves a run
 * no way to finish (see {@link ToolGate}).
 */
public final class ToolApprovalGate implements ToolGate {

    static final Set<String> CONTROL_TOOLS = Set.of(
            "task_complete", "request_clarification", "request_approval", "request_handoff", "load_tools");
    static final int MAX_VALUE_CHARS = 200;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private final Map<String, String> declared;
    private final List<ToolGate> hostGates;

    private ToolApprovalGate(Map<String, String> declared, List<ToolGate> hostGates) {
        this.declared = declared;
        this.hostGates = hostGates;
    }

    /** The gate for a run offering these tools; {@link ToolGate#OPEN} when nothing can be held. */
    public static ToolGate of(List<SkillDefinition> skills, List<ToolGate> hostGates) {
        Map<String, String> declared = new HashMap<>();
        if (skills != null) {
            for (SkillDefinition s : skills) {
                if (s != null && s.requiresApproval() != null) declared.put(s.name(), s.requiresApproval());
            }
        }
        List<ToolGate> hosts = hostGates == null ? List.of()
                : hostGates.stream().filter(g -> g != null && g != ToolGate.OPEN).toList();
        if (declared.isEmpty() && hosts.isEmpty()) return ToolGate.OPEN;
        return new ToolApprovalGate(Map.copyOf(declared), hosts);
    }

    @Override
    public String approvalRequestFor(String toolName, Map<String, Object> params) {
        if (toolName == null || CONTROL_TOOLS.contains(toolName)) return null;
        Map<String, Object> args = params != null ? params : Map.of();
        String template = declared.get(toolName);
        if (template != null) {
            return template.isBlank() ? defaultText(toolName, args) : render(template, args);
        }
        for (ToolGate g : hostGates) {
            String request = g.approvalRequestFor(toolName, args);
            if (request != null) return request;
        }
        return null;
    }

    static String render(String template, Map<String, Object> args) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = args.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "(not given)" : flat(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String defaultText(String toolName, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder("`").append(toolName).append("` needs your approval before it runs.");
        if (!args.isEmpty()) {
            sb.append("\n\nArguments:");
            args.forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(flat(v)));
        }
        return sb.toString();
    }

    private static String flat(Object v) {
        String s = String.valueOf(v).replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_VALUE_CHARS ? s : s.substring(0, MAX_VALUE_CHARS) + "…";
    }
}
