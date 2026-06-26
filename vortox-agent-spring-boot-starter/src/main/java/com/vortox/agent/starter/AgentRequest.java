package com.vortox.agent.starter;

import java.util.Arrays;
import java.util.List;

/**
 * Input for a single agent run. Build via {@link #builder()}.
 *
 * <pre>{@code
 * AgentRequest req = AgentRequest.builder()
 *     .task("Analyse failed logins in the last hour")
 *     .skill("query_pkp_database")
 *     .maxIterations(8)
 *     .build();
 * }</pre>
 */
public final class AgentRequest {

    private final String task;
    private final List<String> skills;
    private final String model;
    private final Integer maxIterations;
    private final String systemPrompt;

    private AgentRequest(Builder b) {
        this.task = b.task;
        this.skills = List.copyOf(b.skills);
        this.model = b.model;
        this.maxIterations = b.maxIterations;
        this.systemPrompt = b.systemPrompt;
    }

    public String         getTask()           { return task; }
    public List<String>   getSkills()         { return skills; }
    public String         getModel()          { return model; }
    public Integer        getMaxIterations()  { return maxIterations; }
    public String         getSystemPrompt()   { return systemPrompt; }

    public static Builder builder()           { return new Builder(); }

    public static final class Builder {
        private String task;
        private List<String> skills = List.of();
        private String model;
        private Integer maxIterations;
        private String systemPrompt;

        public Builder task(String task)                 { this.task = task;                 return this; }
        public Builder skill(String... skills)           { this.skills = Arrays.asList(skills); return this; }
        public Builder skills(List<String> skills)       { this.skills = skills;              return this; }
        public Builder model(String model)               { this.model = model;                return this; }
        public Builder maxIterations(int max)            { this.maxIterations = max;          return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt;  return this; }

        public AgentRequest build() {
            if (task == null || task.isBlank()) throw new IllegalArgumentException("task must not be blank");
            return new AgentRequest(this);
        }
    }
}
