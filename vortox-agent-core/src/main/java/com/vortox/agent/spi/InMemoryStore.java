package com.vortox.agent.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-process {@link MemoryStore}.  Entries live for the lifetime of the JVM.
 * Replace with a database-backed implementation for persistence across restarts.
 */
public class InMemoryStore implements MemoryStore {

    // key: "scope:scopeId:key"  → entry
    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public void store(String scope, String scopeId, String key, String content, String type, int importance) {
        store.put(scope + ":" + scopeId + ":" + key,
                new MemoryEntry(key, content, type, importance));
    }

    @Override
    public List<MemoryEntry> recallForAgent(String agentId, String query) {
        String prefix = "agent:" + agentId + ":";
        String q = query == null ? "" : query.toLowerCase();
        List<MemoryEntry> results = new ArrayList<>();
        for (Map.Entry<String, MemoryEntry> e : store.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            if (q.isEmpty() || e.getValue().content().toLowerCase().contains(q)
                    || e.getValue().key().toLowerCase().contains(q)) {
                results.add(e.getValue());
            }
        }
        results.sort((a, b) -> Integer.compare(b.importance(), a.importance()));
        return results;
    }

    @Override
    public List<MemoryEntry> loadForRun(String runId) {
        String prefix = "task:" + runId + ":";
        List<MemoryEntry> results = new ArrayList<>();
        for (Map.Entry<String, MemoryEntry> e : store.entrySet()) {
            if (e.getKey().startsWith(prefix)) results.add(e.getValue());
        }
        results.sort((a, b) -> Integer.compare(b.importance(), a.importance()));
        return results;
    }
}
