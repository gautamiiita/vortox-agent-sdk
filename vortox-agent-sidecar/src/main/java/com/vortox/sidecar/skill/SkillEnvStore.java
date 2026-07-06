package com.vortox.sidecar.skill;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for skill env vars resolved by the Vortox backend and delivered
 * on each skill sync. Values are kept in memory only — never written to disk.
 *
 * On each sync the store is fully replaced so that deleted skills lose their
 * secrets and updated secrets are picked up without a container restart.
 */
@Component
public class SkillEnvStore {

    private final ConcurrentHashMap<String, Map<String, String>> store = new ConcurrentHashMap<>();

    /** Replace the entire store atomically. Called at the end of each successful skill sync. */
    public void reset(Map<String, Map<String, String>> allEnvVars) {
        store.clear();
        if (allEnvVars != null) {
            allEnvVars.forEach((name, vars) -> {
                if (vars != null && !vars.isEmpty()) {
                    store.put(name, Map.copyOf(vars));
                }
            });
        }
    }

    /** Returns the resolved env vars for a skill, or an empty map if none were synced. */
    public Map<String, String> get(String skillName) {
        return store.getOrDefault(skillName, Collections.emptyMap());
    }

    public int size() {
        return store.size();
    }
}
