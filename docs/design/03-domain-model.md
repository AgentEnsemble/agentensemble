# 03 - Domain Model

This document specifies the full API contracts for all domain objects.

## Agent

```java
@Builder(toBuilder = true)
@Value
public class Agent {

    /** The agent's role/title. Used in prompts and logging. Required. */
    @NonNull String role;

    /** The agent's primary objective. Included in system prompt. Required. */
    @NonNull String goal;

    /** Background context for the agent persona. Included in system prompt. Optional (nullable). */
    String background;

    /**
     * Tools available to this agent. Each entry must be either:
     * - An AgentTool instance (framework interface)
     * - An object with one or more @dev.langchain4j.agent.tool.Tool annotated methods
     * Default: empty list (agent uses pure reasoning, no tool calls).
     */
    @Builder.Default
    List<Object> tools = List.of();

    /** The LLM to use for this agent. Required. Any LangChain4j ChatLanguageModel. */
    @NonNull ChatLanguageModel llm;

    /**
     * Whether this agent can delegate tasks to other agents.
     * Default: false. Reserved for Phase 2.
     */
    @Builder.Default
    boolean allowDelegation = false;

    /** When true, prompts and responses are logged at INFO level. Default: false. */
    @Builder.Default
    boolean verbose = false;

    /**
     * Maximum number of tool call iterations before forcing a final answer.
     * Prevents infinite loops. Must be > 0. Default: 25.
     */
    @Builder.Default
    int maxIterations = 25;

    /**
     * Optional formatting instructions appended to the system prompt.
     * Example: "Always respond in bullet points" or "Format as JSON".
     * Default: empty string (omitted from prompt).
     */
    @Builder.Default
    String responseFormat = "";
}
```

### Build-time Validation

| Field | Rule | Error |
|---|---|---|
| `role` | Non-null, non-blank | `ValidationException("Agent role must not be blank")` |
| `goal` | Non-null, non-blank | `ValidationException("Agent goal must not be blank")` |
| `llm` | Non-null | `ValidationException("Agent LLM must not be null")` |
| `maxIterations` | > 0 | `ValidationException("Agent maxIterations must be > 0, got: {value}")` |
| `tools` entries | Each must be `AgentTool` or have `@Tool` methods | `ValidationException("Tool at index {i} ({className}) is neither an AgentTool nor has @Tool-annotated methods")` |

### Edge Cases

- **Agent with no tools**: Valid. Agent uses pure LLM reasoning with no tool loop.
- **Agent with empty background**: Valid. Prompt builder omits the background section.
- **Agent reused across multiple tasks**: Valid and expected. Same agent definition, separate execution contexts.
- **Agent with maxIterations = 1**: Valid. One tool call attempt, then forced final answer.

---

## Task

```java
@Builder(toBuilder = true)
@Value
public class Task {

    /**
     * What the agent should do. Supports {variable} template placeholders
     * resolved at ensemble.run(inputs) time. Required.
     */
    @NonNull String description;

    /**
     * What the output should look like. Included in the user prompt so the
     * agent knows the expected format/content. Supports templates. Required.
     */
    @NonNull String expectedOutput;

    /** The agent assigned to execute this task. Required. */
    @NonNull Agent agent;

    /**
     * Tasks whose outputs should be included as context when executing this task.
     * All referenced tasks must be executed before this one (validated at run time).
     * Default: empty list.
     */
    @Builder.Default
    List<Task> context = List.of();

    /**
     * The Java class to deserialize the agent's output into.
     * When set, the agent is prompted to produce JSON matching the schema derived
     * from this class, and the output is automatically parsed after execution.
     * If parsing fails, the framework retries up to maxOutputRetries times.
     * Supported: records, POJOs, common JDK types.
     * Unsupported: primitives, void, top-level arrays.
     * Default: null (raw text output only).
     */
    Class<?> outputType;

    /**
     * Maximum number of retry attempts if structured output parsing fails.
     * On each retry the LLM is shown the parse error and required schema.
     * Has no effect when outputType is null.
     * Default: 3. Must be >= 0.
     */
    @Builder.Default
    int maxOutputRetries = 3;
}
```

### Build-time Validation

| Field | Rule | Error |
|---|---|---|
| `description` | Non-null, non-blank | `ValidationException("Task description must not be blank")` |
| `expectedOutput` | Non-null, non-blank | `ValidationException("Task expectedOutput must not be blank")` |
| `agent` | Non-null | `ValidationException("Task agent must not be null")` |
| `context` | No self-references | `ValidationException("Task cannot reference itself in context")` |
| `outputType` | Not a primitive, void, or array (when non-null) | `ValidationException("Task outputType must not be a primitive type: ...")` |
| `maxOutputRetries` | >= 0 | `ValidationException("Task maxOutputRetries must be >= 0, got: {value}")` |

### Edge Cases

- **Task with empty context list**: Valid. Standalone task with no prior context injected.
- **Task referencing an unexecuted context task**: Caught at run time by SequentialWorkflowExecutor (context tasks must appear earlier in the task list).
- **Multiple tasks sharing the same context task**: Valid. The context task's output is read-only, shared safely (immutable).
- **Task description with template variables**: Resolved at `ensemble.run(inputs)` time.

---

## TaskOutput

```java
@Builder
@Value
public class TaskOutput {

    /** The complete text output from the agent. */
    @NonNull String raw;

    /** The original task description (for traceability). */
    @NonNull String taskDescription;

    /** The role of the agent that produced this output. */
    @NonNull String agentRole;

    /** When this task completed (UTC). */
    @NonNull Instant completedAt;

    /** How long the task took to execute. */
    @NonNull Duration duration;

    /** Number of tool invocations during execution. */
    int toolCallCount;

    /**
     * The parsed Java object when the task was configured with outputType.
     * Null when no structured output was requested or parsing was not performed.
     */
    Object parsedOutput;

    /**
     * The Java class used for structured output parsing.
     * Null when no structured output was requested.
     */
    Class<?> outputType;

    /**
     * Return the parsed output as the given type.
     * Throws IllegalStateException when parsedOutput is null or type does not match.
     */
    public <T> T getParsedOutput(Class<T> type) { ... }
}
```

---

## DelegationRequest

Immutable, builder-pattern object constructed by the framework before each delegation invocation (both peer delegation via `AgentDelegationTool` and hierarchical delegation via `DelegateTaskTool`). Provides a structured, correlated representation of the delegation for observability and audit.

```java
@Value
@Builder(toBuilder = true)
public class DelegationRequest {

    /** Auto-generated UUID v4. Correlates with the matching DelegationResponse. */
    @Builder.Default @NonNull String taskId = UUID.randomUUID().toString();

    /** Role of the target agent. */
    @NonNull String agentRole;

    /** Subtask description. */
    @NonNull String taskDescription;

    /** Optional key-value scope providing bounded context. Default: empty map. */
    @Builder.Default Map<String, Object> scope = Collections.emptyMap();

    /** Priority hint. Default: NORMAL. */
    @Builder.Default DelegationPriority priority = DelegationPriority.NORMAL;

    /** Optional description of expected output schema. Default: null. */
    String expectedOutputSchema;

    /** Maximum output parsing retries. Default: 0. */
    @Builder.Default int maxOutputRetries = 0;

    /** Arbitrary observability metadata. Default: empty map. */
    @Builder.Default Map<String, Object> metadata = Collections.emptyMap();
}
```

---

## DelegationResponse

Immutable Java record produced after each delegation attempt. Includes successful delegations and guard-blocked attempts (depth limit, self-delegation, unknown agent) with `DelegationStatus.FAILURE`.

```java
public record DelegationResponse(
    String taskId,          // correlates with DelegationRequest.taskId
    DelegationStatus status,
    String workerRole,
    String rawOutput,       // null on failure
    Object parsedOutput,    // null when no structured output was requested
    Map<String, Object> artifacts,
    List<String> errors,    // empty on success
    Map<String, Object> metadata,
    Duration duration
) {}
```

### DelegationStatus

```java
public enum DelegationStatus {
    SUCCESS,   // task completed and worker produced usable output
    FAILURE,   // task failed; errors list contains details
    PARTIAL    // task completed but output may be incomplete
}
```

### DelegationPriority

```java
public enum DelegationPriority {
    LOW, NORMAL, HIGH, CRITICAL
}
```

---

## EnsembleOutput

```java
@Builder
@Value
public class EnsembleOutput {

    /** The final task's raw output (convenience accessor). */
    @NonNull String raw;

    /** All task outputs in execution order. */
    @NonNull List<TaskOutput> taskOutputs;

    /** Total wall-clock time for the entire ensemble execution. */
    @NonNull Duration totalDuration;

    /** Sum of all tool calls across all tasks. */
    int totalToolCalls;
}
```

---

## HierarchicalConstraints

Immutable value object attached to an `Ensemble` when `workflow = Workflow.HIERARCHICAL`. Governs which worker agents the manager may delegate to, how many times each worker may be called, and the sequence of stages the workflow must progress through. All fields are validated at `ensemble.run()` time before execution begins.

```java
@Value
@Builder
public class HierarchicalConstraints {

    /**
     * Agents the manager MUST delegate to at least once during execution.
     * If any required worker has not received a delegation by the time the
     * manager produces its final answer, a ConstraintViolationException is
     * thrown after the manager run completes (post-execution).
     * Default: empty set.
     */
    @Singular
    Set<String> requiredWorkers;

    /**
     * Agents the manager is permitted to delegate to.
     * When non-empty, delegation to any agent not in this set is blocked and
     * returns a DelegationResponse with DelegationStatus.FAILURE.
     * When empty, all agents registered in the ensemble are allowed.
     * Default: empty set (all workers allowed).
     */
    @Singular
    Set<String> allowedWorkers;

    /**
     * Per-worker cap on the number of delegation calls.
     * Key: agent role string. Value: maximum number of allowed calls (must be > 0).
     * Attempts beyond the cap are blocked and return DelegationStatus.FAILURE.
     * Agents not present in the map are uncapped.
     * Default: empty map (no per-worker cap).
     */
    @Singular
    Map<String, Integer> maxCallsPerWorker;

    /**
     * Total number of delegation calls the manager may make across all workers
     * during a single run(). Once the limit is reached every subsequent
     * delegation attempt is blocked.
     * 0 means unlimited. Must be >= 0.
     * Default: 0 (unlimited).
     */
    @Builder.Default
    int globalMaxDelegations = 0;

    /**
     * Ordered list of stages, where each stage is itself an ordered list of
     * agent roles. The manager must complete all delegations in stage N before
     * delegating to any agent in stage N+1. Useful for enforcing pipeline-style
     * workflows (e.g. research → draft → review).
     * An empty outer list means no stage ordering is enforced.
     * Default: empty list.
     */
    @Singular
    List<List<String>> requiredStages;
}
```

### Usage

`HierarchicalConstraints` is set on an `Ensemble` via the `hierarchicalConstraints` field and is **only evaluated when `workflow = Workflow.HIERARCHICAL`**. It is ignored for all other workflow types.

```java
Ensemble ensemble = Ensemble.builder()
    .workflow(Workflow.HIERARCHICAL)
    .hierarchicalConstraints(
        HierarchicalConstraints.builder()
            .requiredWorker("researcher")
            .allowedWorker("researcher")
            .allowedWorker("writer")
            .maxCallsPerWorker("researcher", 3)
            .maxCallsPerWorker("writer", 2)
            .globalMaxDelegations(10)
            .requiredStage(List.of("researcher"))
            .requiredStage(List.of("writer"))
            .build()
    )
    .agent(managerAgent)
    .agent(researcherAgent)
    .agent(writerAgent)
    .task(orchestrationTask)
    .build();
```

### Cross-Validation Rules (enforced at `run()` time)

| Rule | Condition | Error |
|---|---|---|
| `allowedWorkers` ⊆ ensemble agents | Every role in `allowedWorkers` must match an agent in the `agents` list | `ValidationException("HierarchicalConstraints.allowedWorkers references unknown agent: '{role}'")`|
| `requiredWorkers` ⊆ `allowedWorkers` | When `allowedWorkers` is non-empty, every required worker must also be allowed | `ValidationException("HierarchicalConstraints.requiredWorkers contains '{role}' which is not in allowedWorkers")` |
| `requiredWorkers` ⊆ ensemble agents | Every required worker must be registered in the ensemble | `ValidationException("HierarchicalConstraints.requiredWorkers references unknown agent: '{role}'")`|
| `maxCallsPerWorker` keys ⊆ ensemble agents | Every key must match an agent role in the ensemble | `ValidationException("HierarchicalConstraints.maxCallsPerWorker references unknown agent: '{role}'")`|
| `maxCallsPerWorker` values > 0 | Each per-worker cap must be a positive integer | `ValidationException("HierarchicalConstraints.maxCallsPerWorker value for '{role}' must be > 0, got: {value}")`|
| `globalMaxDelegations` >= 0 | Must not be negative | `ValidationException("HierarchicalConstraints.globalMaxDelegations must be >= 0, got: {value}")`|
| `requiredStages` agent roles ⊆ ensemble agents | Every role in every stage list must match an agent in the ensemble | `ValidationException("HierarchicalConstraints.requiredStages references unknown agent: '{role}'")`|
| `requiredStages` no duplicates across stages | An agent role must not appear in more than one stage | `ValidationException("HierarchicalConstraints.requiredStages contains duplicate agent role '{role}' in multiple stages")`|

### Runtime Enforcement

Guards are applied inside `DelegateTaskTool` on every delegation attempt:

1. **`allowedWorkers` check** — blocks delegation to any worker not in the set (when set is non-empty).
2. **`globalMaxDelegations` check** — blocks all further delegations once the global count is reached (when > 0).
3. **`maxCallsPerWorker` check** — blocks delegation when the per-worker approved attempt count reaches the cap.
4. **`requiredStages` ordering check** — blocks delegation to a stage-N+1 agent before all stage-N agents have completed at least once.

After execution completes, `requiredWorkers` are verified: if any required worker was never delegated to, a `ConstraintViolationException` is thrown carrying the violations list and any partial worker outputs.

---

## Ensemble

```java
@Builder
public class Ensemble {

    /** All agents participating in this ensemble. Required, must not be empty. */
    @Singular
    private final List<Agent> agents;

    /** All tasks to execute, in order. Required, must not be empty. */
    @Singular
    private final List<Task> tasks;

    /** How tasks are executed. Default: SEQUENTIAL. */
    @Builder.Default
    private final Workflow workflow = Workflow.SEQUENTIAL;

    /** When true, raises effective logging to INFO for execution details. */
    @Builder.Default
    private final boolean verbose = false;

    /**
     * Execute the ensemble's tasks with no input variables.
     *
     * @return EnsembleOutput containing all results
     * @throws ValidationException if ensemble configuration is invalid
     * @throws TaskExecutionException if any task fails
     */
    public EnsembleOutput run() {
        return run(Map.of());
    }

    /**
     * Execute the ensemble's tasks with template variable substitution.
     * Variables in task descriptions/expectedOutput like {topic} are replaced
     * with corresponding values from the inputs map.
     *
     * @param inputs Map of variable names to values for template substitution
     * @return EnsembleOutput containing all results
     * @throws ValidationException if ensemble configuration is invalid
     * @throws PromptTemplateException if template variables are missing from inputs
     * @throws TaskExecutionException if any task fails
     */
    public EnsembleOutput run(Map<String, String> inputs) { ... }
}
```

### Validation in run() (Before Execution Begins)

1. **Tasks not empty**: `tasks` must contain at least one task.
   - `ValidationException("Ensemble must have at least one task")`

2. **Agents not empty**: `agents` must contain at least one agent.
   - `ValidationException("Ensemble must have at least one agent")`

3. **Agent membership**: Every task's agent must be present in the `agents` list (identity check).
   - `ValidationException("Task '{description}' references agent '{role}' which is not in the ensemble's agent list")`

4. **No circular context dependencies**: Context references must form a DAG (directed acyclic graph).
   - DFS cycle detection on the context graph.
   - `ValidationException("Circular context dependency detected involving task: '{description}'")`

5. **Context ordering (sequential workflow)**: All context tasks must appear earlier in the task list.
   - `ValidationException("Task '{description}' references context task '{contextDescription}' which appears later in the task list")`

6. **Unused agents**: If an agent is in `agents` but not used by any task, log a warning (not an error).

### Edge Cases

- **`run()` with empty inputs map**: Valid if no templates used. Throws `PromptTemplateException` if templates exist.
- **`run()` called multiple times**: Valid. Each call is independent, no shared mutable state.
- **All tasks assigned to the same agent**: Valid.
- **Agent in list but unused**: Warning logged, execution proceeds.

---

## Workflow

```java
public enum Workflow {

    /** Tasks execute one after another in list order. Output flows forward as context. */
    SEQUENTIAL,

    /** A manager agent delegates tasks to worker agents. Reserved for Phase 2. */
    HIERARCHICAL
}
```
