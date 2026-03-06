# Core Concepts

Understanding these six concepts is sufficient to build any ensemble.

---

## Agent

An **Agent** is an AI entity with a defined role, goal, and optionally a set of tools. When assigned a task, an agent uses its configured LLM to reason and produce an output. Agents can be given tools (such as web search or a calculator) to help them complete their work.

Key properties:
- **role**: The agent's title, used in prompts and logs. Example: `"Senior Research Analyst"`.
- **goal**: The agent's primary objective, included in the system prompt. Example: `"Uncover accurate, well-sourced information"`.
- **background**: Optional persona context. Example: `"You have 10 years experience in technology journalism"`.
- **tools**: Optional list of tool objects the agent can call during execution.
- **allowDelegation**: When `true`, the agent can delegate subtasks to other agents in the ensemble.

See the [Agent Configuration reference](../reference/agent-configuration.md) for all fields.

---

## Task

A **Task** is a unit of work assigned to one agent. It has a description of what to do and an expected output describing what the result should look like. Task descriptions support `{variable}` placeholders that are resolved at run time.

Key properties:
- **description**: What the agent should do. May contain `{variable}` templates.
- **expectedOutput**: What the output should look like (quality guidance for the agent).
- **agent**: The agent assigned to execute this task.
- **context**: Other tasks whose outputs should be fed into this task as prior context.
- **outputType**: Optional Java class to parse the agent's response into. When set, the agent is prompted to produce JSON matching the class schema, and the result is automatically deserialized. Access it with `taskOutput.getParsedOutput(MyRecord.class)`.
- **maxOutputRetries**: How many times to retry if structured output parsing fails (default: 3).

See the [Task Configuration reference](../reference/task-configuration.md).

---

## Ensemble

An **Ensemble** is the top-level orchestrator. It groups agents and tasks, manages execution, handles template variable resolution, creates memory contexts, and returns the combined output. You call `ensemble.run()` or `ensemble.run(Map<String, String> inputs)` to execute.

Key responsibilities:
- Validates that all tasks reference registered agents
- Resolves `{variable}` placeholders in task descriptions and expected outputs
- Creates a `MemoryContext` for the run when memory is configured
- Selects and runs the correct `WorkflowExecutor`
- Returns an `EnsembleOutput` with all task results

See the [Ensemble Configuration reference](../reference/ensemble-configuration.md).

---

## Workflow

A **Workflow** is the execution strategy used by the ensemble. As of v2.0.0, **declaring a
workflow is optional** -- the framework infers the right strategy from your task declarations.

### Inference (default when no `.workflow(...)` call is made)

| Condition | Inferred strategy |
|---|---|
| No task has a `context` dependency on another task | `SEQUENTIAL` |
| Any task declares `context(...)` on another ensemble task | `PARALLEL` (DAG-based) |

### SEQUENTIAL

Tasks run one after another in list order. Each task can declare `context` dependencies on prior
tasks; those outputs are injected into the dependent agent's prompt.

```
Task 1 -> Task 2 -> Task 3 (uses output of Task 1 and Task 2)
```

### PARALLEL

Tasks with no unmet dependencies run concurrently using Java 21 virtual threads. The dependency
graph is derived automatically from each task's `context` declarations.

```
Task A ----+
            +--> Task C (depends on A + B)
Task B ----+
```

### HIERARCHICAL

A virtual Manager agent is automatically created. The manager receives the full task list and the capabilities of all worker agents. It uses a `delegateTask` tool to assign tasks to workers and then synthesizes a final result from their outputs.

```
Manager -> delegates -> Worker A
        -> delegates -> Worker B
        -> synthesizes final output
```

Optionally, add `HierarchicalConstraints` to impose deterministic guardrails (required workers, allowed workers, per-worker caps, stage ordering) while keeping the workflow LLM-directed. See the [Delegation guide](../guides/delegation.md#hierarchical-constraints).

See the [Workflows guide](../guides/workflows.md).

---

## Memory

**Memory** lets agents share and persist context across tasks and ensemble runs. Three types are available:

- **Short-term memory**: Within a single `run()` call, all task outputs are accumulated and injected into subsequent agents' prompts. This removes the need to declare explicit `context` dependencies.
- **Long-term memory**: Task outputs are stored in a vector store after each run. Before each task, relevant past memories are retrieved by semantic similarity and injected into the agent's prompt.
- **Entity memory**: A user-populated key-value store of known facts about named entities (people, companies, concepts, etc.). All stored facts are injected into every agent's prompt.

Memory is optional and configured via `EnsembleMemory` on the ensemble builder.

See the [Memory guide](../guides/memory.md).

---

## Delegation

**Delegation** allows agents to hand off subtasks to other agents during execution. When an agent has `allowDelegation = true`, a `delegate` tool is automatically injected into its tool list. The agent can call this tool with a target agent role and a task description, and the framework pauses the caller, executes the subtask with the target agent, and returns the result.

Guards prevent:
- An agent from delegating to itself
- Delegation to an unknown agent role
- Infinite delegation chains (configurable `maxDelegationDepth`, default 3)

See the [Delegation guide](../guides/delegation.md).

---

## Guardrails

**Guardrails** are pluggable validation hooks configured per task. They give you control over what enters and exits agent execution without modifying agent prompts or task logic.

- **Input guardrails** run before the LLM call. If any fails, execution is blocked immediately and `GuardrailViolationException` is thrown -- no API call is made.
- **Output guardrails** run after the agent produces a response. If any fails, the response is rejected and `GuardrailViolationException` is thrown.

Both types implement functional interfaces (`InputGuardrail`, `OutputGuardrail`) and return `GuardrailResult.success()` or `GuardrailResult.failure(reason)`.

```java
var task = Task.builder()
    .description("Summarize the document")
    .expectedOutput("A concise summary")
    .agent(writer)
    .inputGuardrails(List.of(input -> {
        return input.taskDescription().length() < 10
            ? GuardrailResult.failure("Task description too short")
            : GuardrailResult.success();
    }))
    .build();
```

See the [Guardrails guide](../guides/guardrails.md).
