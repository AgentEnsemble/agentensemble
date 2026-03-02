# Ensemble Configuration Reference

All fields available on `Ensemble.builder()`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `agents` | `List<Agent>` | Yes | -- | All agents participating in this ensemble. Add with `.agent(a)` (singular) or `.agents(list)`. |
| `tasks` | `List<Task>` | Yes | -- | All tasks to execute. Add with `.task(t)` (singular) or `.tasks(list)`. |
| `workflow` | `Workflow` | No | `SEQUENTIAL` | Execution strategy. `SEQUENTIAL` or `HIERARCHICAL`. |
| `managerLlm` | `ChatModel` | No | First agent's LLM | LLM for the auto-created Manager agent (hierarchical workflow only). |
| `managerMaxIterations` | `int` | No | `20` | Maximum tool-call iterations for the Manager agent. Must be greater than zero. |
| `verbose` | `boolean` | No | `false` | When `true`, elevates all agent logging to INFO level. |
| `memory` | `EnsembleMemory` | No | `null` | Memory configuration. See [Memory Configuration reference](memory-configuration.md). |
| `maxDelegationDepth` | `int` | No | `3` | Maximum peer-delegation depth. Applies when agents have `allowDelegation = true`. Must be greater than zero. |

---

## Validation

At `Ensemble.run()` time:

- At least one agent must be registered
- At least one task must be registered
- Every task's `agent` must be in the ensemble's `agents` list
- No circular context dependencies
- Context task ordering is valid (sequential workflow only)
- `maxDelegationDepth` must be greater than zero

---

## Output: `EnsembleOutput`

`ensemble.run()` returns an `EnsembleOutput` with:

| Method | Type | Description |
|---|---|---|
| `getRaw()` | `String` | Raw text of the final task output (or manager synthesis in hierarchical workflow) |
| `getTaskOutputs()` | `List<TaskOutput>` | All task outputs in execution order |
| `getTotalDuration()` | `Duration` | Wall-clock time for the entire run |
| `getTotalToolCalls()` | `int` | Total number of tool calls across all agents |

Each `TaskOutput` contains:

| Method | Type | Description |
|---|---|---|
| `getRaw()` | `String` | Raw text output from the agent |
| `getAgentRole()` | `String` | Role of the agent that produced this output |
| `getTaskDescription()` | `String` | Description of the task (after template resolution) |
| `getDuration()` | `Duration` | Time taken for this task |
| `getToolCallCount()` | `int` | Number of tool calls made for this task |
| `getCompletedAt()` | `Instant` | Timestamp when this task completed |

---

## Full Example

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .agent(editor)
    .task(researchTask)
    .task(writeTask)
    .task(editTask)
    .workflow(Workflow.SEQUENTIAL)
    .verbose(false)
    .memory(EnsembleMemory.builder().shortTerm(true).build())
    .maxDelegationDepth(2)
    .build()
    .run(Map.of("topic", "AI agents", "audience", "developers"));
```

---

## Template Variables

Call `ensemble.run(Map<String, String> inputs)` to resolve `{variable}` placeholders in all task descriptions and expected outputs. See [Template Variables guide](../guides/template-variables.md).
