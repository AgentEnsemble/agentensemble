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
  net.agentensemble.ratelimit.RateLimitTimeoutException
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

## `GuardrailViolationException`

**Thrown by:** `AgentExecutor` when an `InputGuardrail` or `OutputGuardrail` configured on a `Task` returns a failure result.

Propagates through the workflow executor and is wrapped in `TaskExecutionException` -- catch `TaskExecutionException` and inspect `getCause()` to detect guardrail failures.

Input violations are thrown before any LLM call is made. Output violations are thrown after the agent response (and after structured output parsing when `outputType` is set).

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getGuardrailType()` | `GuardrailType` | `INPUT` (pre-execution) or `OUTPUT` (post-execution) |
| `getViolationMessage()` | `String` | The failure reason returned by the guardrail |
| `getTaskDescription()` | `String` | Description of the blocked task |
| `getAgentRole()` | `String` | Role of the agent assigned to the task |

---

## `RateLimitTimeoutException`

**Package:** `net.agentensemble.ratelimit`

**Thrown by:** `RateLimitedChatModel`

A thread waited for a rate-limit token longer than the configured `waitTimeout` and no token
became available. Propagates up through `AgentExecutor` and is wrapped in `TaskExecutionException`.

**Methods:**
| Method | Type | Description |
|---|---|---|
| `getRateLimit()` | `RateLimit` | The rate limit being enforced when the timeout occurred |
| `getWaitTimeout()` | `Duration` | The wait timeout that was exceeded |

**Remediation:** increase `waitTimeout` on `RateLimitedChatModel`, reduce concurrency, or
increase the `RateLimit` to allow more requests per period.

See the [Rate Limiting guide](../guides/rate-limiting.md) for details.

---

## Error Handling Guide

See the [Error Handling guide](../guides/error-handling.md) for patterns and examples.
