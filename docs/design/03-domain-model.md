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
}
```

### Build-time Validation

| Field | Rule | Error |
|---|---|---|
| `description` | Non-null, non-blank | `ValidationException("Task description must not be blank")` |
| `expectedOutput` | Non-null, non-blank | `ValidationException("Task expectedOutput must not be blank")` |
| `agent` | Non-null | `ValidationException("Task agent must not be null")` |
| `context` | No self-references | `ValidationException("Task cannot reference itself in context")` |

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
