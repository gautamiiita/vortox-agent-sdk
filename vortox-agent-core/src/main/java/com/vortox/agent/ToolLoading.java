package com.vortox.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TOOLS LOADED WHEN THEY ARE NEEDED, not sent in full on every turn.
 *
 * <p>A tool the host marks {@code "defer_loading": true} is declared to the API but kept out of the
 * model's context. The model is told what exists through one always-loaded tool, {@code load_tools},
 * whose description names each deferred tool and says in a line what it is for. Calling it surfaces the
 * named tools from the next turn, by a {@code tool_addition} block on a system-role message appended to
 * the conversation — appended, so the cached prefix is untouched (AnthropicClient, "Deferred tools").
 *
 * <p>What was surfaced is read back from the conversation itself, so a resumed or compacted run keeps
 * the tools it had loaded without anybody remembering them for it.
 */
final class ToolLoading {

    static final String NAME = "load_tools";

    private ToolLoading() {}

    /** The names of the tools declared deferred, in their order. */
    static Set<String> deferredNames(List<Map<String, Object>> tools) {
        Set<String> out = new LinkedHashSet<>();
        if (tools == null) return out;
        for (Map<String, Object> t : tools) {
            if (t != null && Boolean.TRUE.equals(t.get("defer_loading")) && t.get("name") instanceof String n) out.add(n);
        }
        return out;
    }

    /** The definition of {@code load_tools}, naming every deferred tool with the first sentence of its description. */
    static Map<String, Object> definition(List<Map<String, Object>> tools) {
        StringBuilder sb = new StringBuilder("Load tools that are available but not loaded yet, by name, so you can "
                + "call them from your next turn. Load only what this task needs; several in one call. Available:\n");
        for (Map<String, Object> t : tools) {
            if (t == null || !Boolean.TRUE.equals(t.get("defer_loading"))) continue;
            sb.append("- ").append(t.get("name")).append(": ").append(firstSentence(String.valueOf(t.getOrDefault("description", "")))).append('\n');
        }
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("type", "array");
        names.put("items", Map.of("type", "string"));
        names.put("description", "The names of the tools to load, from the list above.");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("names", names));
        schema.put("required", List.of("names"));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", NAME);
        def.put("description", sb.toString().trim());
        def.put("input_schema", schema);
        return def;
    }

    /** The tools already surfaced in {@code messages}. */
    @SuppressWarnings("unchecked")
    static Set<String> surfaced(List<Map<String, Object>> messages) {
        Set<String> out = new LinkedHashSet<>();
        if (messages == null) return out;
        for (Map<String, Object> m : messages) {
            if (m == null || !"system".equals(m.get("role")) || !(m.get("content") instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> block && "tool_addition".equals(block.get("type"))
                        && block.get("tool") instanceof Map<?, ?> ref && ref.get("name") instanceof String n) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /**
     * Answers a {@code load_tools} call and adds what is to be surfaced to {@code pending}.
     * The reply says what loaded, what was already loaded and what does not exist.
     */
    static String load(Object namesInput, Set<String> deferred, Set<String> surfaced, Set<String> pending) {
        List<String> asked = new ArrayList<>();
        if (namesInput instanceof Collection<?> c) { for (Object o : c) if (o != null) asked.add(String.valueOf(o).trim()); }
        else if (namesInput instanceof String s) { for (String p : s.split("[,\\s]+")) if (!p.isBlank()) asked.add(p.trim()); }
        List<String> loaded = new ArrayList<>(), already = new ArrayList<>(), unknown = new ArrayList<>();
        for (String n : asked) {
            if (surfaced.contains(n) || pending.contains(n)) already.add(n);
            else if (deferred.contains(n)) { pending.add(n); loaded.add(n); }
            else unknown.add(n);
        }
        StringBuilder sb = new StringBuilder();
        if (!loaded.isEmpty()) sb.append("Loaded: ").append(String.join(", ", loaded)).append(". Call them from your next turn.");
        if (!already.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append("Already loaded: ").append(String.join(", ", already)).append('.');
        if (!unknown.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append("Not a deferred tool: ").append(String.join(", ", unknown))
                .append(" — tools you can already see need no loading.");
        return sb.length() > 0 ? sb.toString() : "Name at least one tool to load.";
    }

    /** The system-role message that surfaces {@code names}. */
    static Map<String, Object> additionMessage(Collection<String> names) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String n : names) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("type", "tool_reference");
            ref.put("name", n);
            Map<String, Object> add = new LinkedHashMap<>();
            add.put("type", "tool_addition");
            add.put("tool", ref);
            blocks.add(add);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", blocks);
        return m;
    }

    private static String firstSentence(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        int dot = t.indexOf(". ");
        String first = dot > 0 ? t.substring(0, dot + 1) : t;
        return first.length() > 160 ? first.substring(0, 159).strip() + "…" : first;
    }
}
