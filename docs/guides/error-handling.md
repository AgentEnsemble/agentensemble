# Error Handling

AgentEnsemble uses a hierarchy of unchecked exceptions. All exceptions extend `AgentEnsembleException`, making it easy to catch any framework exception with a single catch block or handle specific cases individually.

---

## Exception Hierarchy

```
AgentEnsembleException (base)
  ValidationException          -- invalid configuration at build/run time
  TaskExecutionException       -- a task failed during execution
  AgentExecutionException      -- an LLM call failed
  MaxIterationsExceededException  -- agent exceeded its tool-call limit
  PromptTemplateException      -- unresolved template variables
  ToolExecutionException       -- a tool call failed
  ConstraintViolationException -- required workers were not called (hierarchical workflow)
```

---

## `ValidationException`

Thrown when the ensemble or its components are configured incorrectly. This exception is thrown before any LLM calls.

Common causes:
- Missing required fields (`role`, `goal`, `llm`, `description`, `expectedOutput`, `agent`)
- A task references an agent not registered with the ensemble
- Context tasks appear after the tasks that depend on them (sequential workflow)
- Circular context dependencies
- `maxIterations`, `managerMaxIterations`, or `maxDelegationDepth` set to zero or negative
- `EnsembleMemory` built with no memory type enabled

```java
try {
    EnsembleOutput output = ensemble.run();
} catch (ValidationException e) {
    System.err.println("Configuration error: " + e.getMessage());
}
```

---

## `TaskExecutionException`

Thrown when a task fails during execution. Contains:
- The description of the failed task
- The role of the agent assigned to it
- A list of `TaskOutput` objects for tasks that completed before the failure

```java
try {
    EnsembleOutput output = ensemble.run();
} catch (TaskExecutionException e) {
    System.err.println("Task failed: " + e.getTaskDescription());
    System.err.println("Agent: " + e.getAgentRole());
    System.err.println("Cause: " + e.getCause().getMessage());

    // Access results from tasks that completed before the failure
    for (TaskOutput completed : e.getCompletedTaskOutputs()) {
        System.out.println("Completed: " + completed.getTaskDescription());
        System.out.println("Output: " + completed.getRaw());
    }
}
```

---

## `AgentExecutionException`

Thrown when the LLM call itself fails (network error, API error, timeout). Contains the agent role and task description.

```java
catch (AgentExecutionException e) {
    System.err.println("Agent '" + e.getAgentRole() + "' failed on task '"
        + e.getTaskDescription() + "': " + e.getMessage());
}
```

---

## `MaxIterationsExceededException`

Thrown when an agent exceeds its `maxIterations` limit. Contains the configured limit and the actual count.

```java
catch (MaxIterationsExceededException e) {
    System.err.println("Agent '" + e.getAgentRole() + "' exceeded its iteration limit.");
    System.err.println("Configured limit: " + e.getMaxIterations());
    System.err.println("Actual iterations: " + e.getActualIterations());
}
```

To prevent this, either increase `maxIterations` on the agent or give the agent fewer, more focused tools.

---

## `PromptTemplateException`

Thrown when a task description or expected output contains `{variable}` placeholders that were not resolved because the corresponding key was not in the inputs map.

```java
catch (PromptTemplateException e) {
    System.err.println("Missing template variables: " + e.getMissingVariables());
    // e.g., "Missing template variables: [topic, year]"
}
```

---

## `ConstraintViolationException`

Thrown after a hierarchical workflow manager completes when one or more workers listed in `HierarchicalConstraints` were never called during the run. This signals that the manager did not satisfy the required delegation constraints before finishing. Contains:
- A list of violation descriptions via `getViolations()`
- A list of `TaskOutput` objects for tasks that did complete via `getCompletedTaskOutputs()`

```java
try {
    EnsembleOutput output = ensemble.run(inputs);
} catch (ConstraintViolationException e) {
    System.err.println("Constraint violations detected:");
    for (String violation : e.getViolations()) {
        System.err.println("  - " + violation);
    }

    // Partial results are still available for tasks that completed
    for (TaskOutput completed : e.getCompletedTaskOutputs()) {
        System.out.println("Completed: " + completed.getTaskDescription());
        System.out.println("Output: " + completed.getRaw());
    }
}
```

---

## `ToolExecutionException`

Thrown when a tool fails to execute. This is typically wrapped inside an `AgentExecutionException`.

---

## Catching All Framework Exceptions

```java
try {
    EnsembleOutput output = ensemble.run(inputs);
} catch (AgentEnsembleException e) {
    log.error("Ensemble failed: {}", e.getMessage(), e);
}
```

---

## Exit Reasons

`EnsembleOutput.getExitReason()` (and the convenience method `isComplete()`) describe how the pipeline terminated:

| `ExitReason` | Meaning |
|---|---|
| `COMPLETED` | All tasks ran to completion normally. `isComplete()` returns `true`. |
| `USER_EXIT_EARLY` | A human reviewer explicitly chose to stop the pipeline at a review gate, or a `HumanInputTool` returned ExitEarly. |
| `TIMEOUT` | A review gate timeout expired and the configured `onTimeout` action was `EXIT_EARLY`. Distinct from a user-initiated exit. |
| `ERROR` | An unrecoverable exception terminated the pipeline. Partial results are available in the `TaskExecutionException`. |

```java
EnsembleOutput output = ensemble.run();

switch (output.getExitReason()) {
    case COMPLETED:
        System.out.println("All done: " + output.getRaw());
        break;
    case USER_EXIT_EARLY:
        System.out.println("User stopped the pipeline after "
            + output.completedTasks().size() + " task(s)");
        System.out.println("Last output: " + output.getRaw());
        break;
    case TIMEOUT:
        System.out.println("Review gate timed out after "
            + output.completedTasks().size() + " task(s)");
        break;
    case ERROR:
        // This case is typically handled via exception -- see TaskExecutionException
        break;
}
```

---

## Partial Results

When the pipeline stops early -- whether due to a user exit-early, a timeout, or an error --
the tasks that completed before the stop are always preserved in the `EnsembleOutput`.

### Checking completion

```java
EnsembleOutput output = ensemble.run();
if (!output.isComplete()) {
    System.out.println("Pipeline stopped early: " + output.getExitReason());
    System.out.println("Completed: " + output.completedTasks().size() + " task(s)");
}
```

### Accessing completed task outputs

```java
// All completed task outputs in execution order
List<TaskOutput> done = output.completedTasks();

// The last completed output
output.lastCompletedOutput().ifPresent(o ->
    System.out.println("Last: " + o.getRaw()));

// A specific task's output (identity-based lookup)
output.getOutput(researchTask).ifPresent(o ->
    System.out.println("Research: " + o.getRaw()));
```

`getOutput(Task task)` uses object identity for the lookup: pass the same `Task` instance that
was given to `Ensemble.builder().task(...)`. If the task completed, its output is returned; if it
was skipped or never started, `Optional.empty()` is returned.

### Partial results on exception

When a `TaskExecutionException` is thrown, the partial results from successfully completed tasks are available via `e.getCompletedTaskOutputs()`. This allows you to save or display intermediate work even when the ensemble fails partway through.

```java
try {
    EnsembleOutput output = ensemble.run(inputs);
    saveResults(output);
} catch (TaskExecutionException e) {
    // Save whatever was completed before the failure
    for (TaskOutput partial : e.getCompletedTaskOutputs()) {
        savePartialResult(partial);
    }
    // Alert on the failure
    alertOnFailure(e.getTaskDescription(), e.getAgentRole());
}
```

---

## Retry Patterns

AgentEnsemble does not have built-in retry logic. For transient failures (e.g., API rate limits), implement retry at the call site:

```java
int attempts = 0;
EnsembleOutput output = null;

while (attempts < 3) {
    try {
        output = ensemble.run(inputs);
        break;
    } catch (AgentExecutionException e) {
        attempts++;
        if (attempts == 3) throw e;
        Thread.sleep(1000L * attempts);   // exponential back-off
    }
}
```

For production use, consider integrating a resilience library such as Resilience4j.
