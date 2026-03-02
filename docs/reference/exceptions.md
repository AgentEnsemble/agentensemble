# Exceptions Reference

All AgentEnsemble exceptions extend `AgentEnsembleException`, which itself extends `RuntimeException`. All exceptions are unchecked.

---

## Hierarchy

```
java.lang.RuntimeException
  net.agentensemble.exception.AgentEnsembleException
    ValidationException
    TaskExecutionException
    AgentExecutionException
    MaxIterationsExceededException
    PromptTemplateException
    ToolExecutionException
```

---

## `AgentEnsembleException`

**Package:** `net.agentensemble.exception`

Base class for all framework exceptions. Catch this to handle any AgentEnsemble error with a single catch block.

```java
catch (AgentEnsembleException e) {
    log.error("Ensemble error: {}", e.getMessage(), e);
}
```

---

## `ValidationException`

**Thrown by:** `Agent.build()`, `Task.build()`, `EnsembleMemory.build()`, `Ensemble.run()`

Indicates an invalid configuration. Always thrown before any LLM calls, so there is no partial state.

**Common messages:**
- `"Agent role must not be blank"`
- `"Agent llm must not be null"`
- `"Agent maxIterations must be > 0, got: 0"`
- `"Ensemble must have at least one task"`
- `"Task 'X' references agent 'Y' which is not in the ensemble's agent list"`
- `"Ensemble maxDelegationDepth must be > 0, got: 0"`
- `"Circular context dependency detected involving task: 'X'"`
- `"EnsembleMemory must have at least one memory type enabled"`

---

## `TaskExecutionException`

**Thrown by:** `SequentialWorkflowExecutor`

A task failed during execution. Contains partial results from tasks that completed before the failure.

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getTaskDescription()` | `String` | Description of the task that failed |
| `getAgentRole()` | `String` | Role of the agent assigned to the failed task |
| `getCompletedTaskOutputs()` | `List<TaskOutput>` | Outputs of tasks that completed successfully before the failure |

---

## `AgentExecutionException`

**Thrown by:** `AgentExecutor`

The LLM call failed (API error, network error, timeout, etc.).

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getAgentRole()` | `String` | Role of the agent that failed |
| `getTaskDescription()` | `String` | Description of the task being executed |

---

## `MaxIterationsExceededException`

**Thrown by:** `AgentExecutor`

The agent exceeded its `maxIterations` limit and did not produce a final answer after receiving stop messages.

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getAgentRole()` | `String` | Role of the agent |
| `getTaskDescription()` | `String` | Description of the task |
| `getMaxIterations()` | `int` | The configured limit |
| `getActualIterations()` | `int` | The actual number of iterations |

---

## `PromptTemplateException`

**Thrown by:** `TemplateResolver` during `Ensemble.run()`

One or more `{variable}` placeholders in task descriptions or expected outputs were not resolved because the corresponding key was not in the inputs map.

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getMissingVariables()` | `List<String>` | Names of the unresolved variables |

---

## `ToolExecutionException`

**Thrown by:** `LangChain4jToolAdapter`

A `@Tool`-annotated method threw an exception during execution. The exception is typically wrapped in an `AgentExecutionException`.

---

## Error Handling Guide

See the [Error Handling guide](../guides/error-handling.md) for patterns and examples.
