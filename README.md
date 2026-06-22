# vortox-agent-sdk

A self-contained Java library for building autonomous LLM agents using the **ReAct** (Reasoning + Acting) pattern over the Anthropic Messages API.

No Spring. No database. Drop it into any Java project and the agent loop works.

```java
AgentConfig config = AgentConfig.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .systemPrompt("You are a data analyst. Use the provided tools to answer questions.")
    .tools(myToolDefinitions)
    .toolExecutor((name, params, runId) -> myDispatcher.invoke(name, params))
    .build();

AgentResult result = new ReactLoop(config).run("How many orders were placed last week?");

if (result.isSuccess()) {
    System.out.println(result.response());
}
```

---

## What it does

`ReactLoop` runs the agent loop:

1. Send the task + tools to Claude
2. If Claude calls tools → execute them (in parallel), feed results back
3. Repeat until Claude calls `task_complete`, `request_clarification`, `request_approval`, or `request_handoff`
4. Return a typed `AgentResult`

The loop handles conversation management, prompt caching, transient error retries, conversation pruning, and the outcome self-check gate. You supply the API key, the system prompt, the tools, and a `ToolExecutor` that knows how to run them.

---

## Installation

The library is not yet on Maven Central. Clone and install locally:

```bash
git clone https://github.com/gautamiiita/vortox-agent-sdk.git
cd vortox-agent-sdk
mvn install
```

Then add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.vortox</groupId>
    <artifactId>vortox-agent-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Runtime dependencies:** `jackson-databind` and `slf4j-api` only. Java 21+.

---

## Core concepts

### AgentConfig

Everything the loop needs, built once:

```java
AgentConfig config = AgentConfig.builder()
    .apiKey("sk-ant-...")
    .model("claude-sonnet-4-6")        // default
    .maxTokens(4096)                    // default
    .maxIterations(50)
    .systemPrompt("You are ...")
    .tools(toolDefinitions)             // List<Map<String,Object>> in Claude tool_use format
    .toolExecutor(myExecutor)
    .memoryStore(myMemoryStore)         // optional — defaults to in-memory
    .activityListener(myListener)       // optional — observes loop events
    .taskSpawner(mySpawner)             // optional — enables spawn_task built-in
    .build();
```

### ReactLoop

Stateless and thread-safe — one instance per agent config, many tasks in parallel:

```java
ReactLoop loop = new ReactLoop(config);

// Fresh task
AgentResult r = loop.run("Summarise the Q3 report.");

// With a self-verification gate — agent must confirm it met the outcome before SUCCESS is accepted
AgentResult r = loop.runWithExpectedOutcome(task, runId, "All unit tests pass and coverage > 80%");

// Resume after max-iterations with prior conversation snapshot
AgentResult r = loop.run(task, runId, expectedOutcome, priorMessages);

// Resume after a human approved/rejected an approval gate
AgentResult r = loop.resumeAfterApproval(runId, snapshot, assistantContent, toolUseId, decision);
```

### AgentResult

```java
switch (result.status()) {
    case SUCCESS             -> System.out.println(result.response());
    case PARTIAL             -> handlePartial(result.response(), result.conversationHistory());
    case CLARIFICATION_NEEDED -> askUser(result.clarificationQuestion());
    case APPROVAL_NEEDED     -> pauseForHuman(result);   // store snapshot, resume later
    case HANDOFF             -> routeTo(result.handoffTargetRole(), result.handoffContext());
    case ERROR               -> log.error(result.error());
}
```

---

## SPI interfaces

Implement these to connect the SDK to your infrastructure. All are optional except `ToolExecutor` when you have non-built-in tools.

### ToolExecutor

```java
@FunctionalInterface
public interface ToolExecutor {
    // Return the tool result as a string. Throw ToolExecutionException to signal is_error=true.
    String execute(String toolName, Map<String, Object> params, String runId);
}
```

### MemoryStore

```java
public interface MemoryStore {
    void store(String scope, String scopeId, String key, String content, String type, int importance);
    List<MemoryEntry> recallForAgent(String agentId, String query);
    List<MemoryEntry> loadForRun(String runId);
}
```

The default `InMemoryStore` (backed by `ConcurrentHashMap`) is used when no store is configured.

### ActivityListener

```java
public interface ActivityListener {
    default void onIteration(String runId, int iteration, int maxIterations, String description) {}
    default void onToolCall(String runId, String toolName, Map<String, Object> params) {}
    default void onToolResult(String runId, String toolName, boolean success, long durationMs) {}
    default void onComplete(String runId, AgentResult result) {}
    default void onError(String runId, String description) {}
}
```

### TaskSpawner

```java
@FunctionalInterface
public interface TaskSpawner {
    // Called when the agent uses the spawn_task built-in. Return a task ID or status string.
    String spawn(Map<String, Object> params, String callerRunId);
}
```

---

## Built-in tools

These are added to the tool list automatically and handled by the loop. You do not need to define or execute them.

| Tool | What it does | Config flag |
|---|---|---|
| `task_complete` | Ends the loop with SUCCESS / PARTIAL / FAILED | always on |
| `request_clarification` | Pauses and returns `CLARIFICATION_NEEDED` | `enableClarification(true)` |
| `request_approval` | Pauses for human approval, resumes via `resumeAfterApproval()` | `enableApproval(true)` |
| `request_handoff` | Routes to another agent role | `enableHandoff(true)` |
| `remember_memory` | Stores a memory via `MemoryStore` | `enableMemoryTools(true)` |
| `recall_memory` | Searches memories via `MemoryStore` | `enableMemoryTools(true)` |
| `write_task_memory` | Writes context for the next agent in a task | `enableMemoryTools(true)` |
| `read_task_memory` | Reads context written by prior agents | `enableMemoryTools(true)` |
| `execute_command` | Runs a shell command on the host | `enableExecuteCommand(true)` |
| `spawn_task` | Creates a sub-task via `TaskSpawner` | requires `taskSpawner(...)` |

All flags default to `true` except `enableSpawnTask` (requires an explicit `TaskSpawner`). Caller-supplied tool definitions with the same name take precedence over SDK defaults — you can override a built-in's description without disabling its built-in handling.

---

## Overriding built-in tool behaviour

Set `enableExecuteCommand(false)` and include your own `execute_command` definition in `.tools(...)` to route the call through your `ToolExecutor` instead (e.g. to run commands inside a Docker container):

```java
AgentConfig config = AgentConfig.builder()
    .enableExecuteCommand(false)
    .tools(List.of(myExecuteCommandDefinition))   // your richer description
    .toolExecutor((name, params, runId) -> {
        if ("execute_command".equals(name)) return containerExec(params);
        return mySkillDispatcher.run(name, params);
    })
    .build();
```

---

## Conversation pruning

The loop automatically truncates old conversation rounds to control token growth:

- Last 5 tool-result rounds kept in full
- Older tool-result content truncated to 400 chars
- Older assistant reasoning text truncated to 200 chars
- `report` fields (structured skill output) kept up to 12 000 chars

---

## How this is used in Vortox

[`task-coordination-dashboard`](https://github.com/gautamiiita/task-coordination-dashboard) uses this SDK as its agent execution layer. `AgentRunner` builds an `AgentConfig` per task with four adapter classes that connect the SDK interfaces to Spring services:

- `VortoxToolExecutor` → `SkillExecutorService`, `MCPClientService`, `NotificationDispatcher`
- `VortoxMemoryStore` → `AgentProjectMemoryService`
- `VortoxActivityListener` → `AgentActivityService`
- `VortoxTaskSpawner` → `TaskRepository` + `TaskEventPublisher`

The loop, conversation management, and all built-in tool handling live here. Vortox contributes the system prompt, skill definitions, and wiring to its persistence layer.

---

## License

MIT
