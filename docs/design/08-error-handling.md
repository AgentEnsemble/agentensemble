# 08 - Error Handling

This document specifies the exception hierarchy, when each exception is thrown, and the recovery strategies available to users.

## Exception Hierarchy

```
java.lang.RuntimeException
  +-- AgentEnsembleException                  (base for all framework exceptions)
        +-- ValidationException               (invalid configuration)
        +-- TaskExecutionException             (task-level failure)
        +-- ConstraintViolationException       (post-execution hierarchical constraint failure)
        +-- AgentExecutionException            (agent-level failure)
        +-- ToolExecutionException             (tool infrastructure failure)
        +-- MaxIterationsExceededException     (agent stuck in tool loop)
        +-- PromptTemplateException            (template variable error)
```

All exceptions are **unchecked** (extend `RuntimeException`). This is a deliberate design choice: AgentEnsemble is a framework, and forcing users to catch-or-declare everywhere would make the API cumbersome. Users who want to handle errors can catch specific types; others can let exceptions propagate naturally.

## Exception Definitions

### AgentEnsembleException

Base exception for all framework errors.

```java
public class AgentEnsembleException extends RuntimeException {
    public AgentEnsembleException(String message) { ... }
    public AgentEnsembleException(String message, Throwable cause) { ... }
    public AgentEnsembleException(Throwable cause) { ... }
}
```

### ValidationException

Thrown when ensemble, agent, or task configuration is invalid. This is always a user error (incorrect configuration).

```java
public class ValidationException extends AgentEnsembleException {
    public ValidationException(String message) { ... }
}
```

**When thrown:**
- Agent build with null/blank role, goal, or null LLM
- Agent build with invalid maxIterations (<= 0)
- Agent build with invalid tool objects
- Ensemble.run() with empty tasks or agents list
- Ensemble.run() when a task references an agent not in the ensemble
- Ensemble.run() with circular context dependencies
- Ensemble.run() with context ordering violations (sequential workflow)
- Duplicate tool names detected

### TaskExecutionException

Thrown when a task fails during execution. Carries context about the failure and any completed work.

```java
public class TaskExecutionException extends AgentEnsembleException {

    /** Description of the task that failed. */
    private final String taskDescription;

    /** Role of the agent that was executing the task. */
    private final String agentRole;

    /**
     * Outputs from tasks that completed successfully before this failure.
     * Allows partial recovery of work.
     */
    private final List<TaskOutput> completedTaskOutputs;

    public TaskExecutionException(String message, String taskDescription,
            String agentRole, List<TaskOutput> completedTaskOutputs) { ... }

    public TaskExecutionException(String message, String taskDescription,
            String agentRole, List<TaskOutput> completedTaskOutputs,
            Throwable cause) { ... }

    // Getters for all fields
}
```

**When thrown:**
- Any unrecoverable error during task execution in the WorkflowExecutor
- Wraps `AgentExecutionException` or `MaxIterationsExceededException` with additional context

**Recovery:**
- `getCompletedTaskOutputs()` returns all outputs from tasks that succeeded before the failure
- Users can inspect these for partial results
- `getCause()` reveals the underlying error (LLM timeout, agent stuck, etc.)

### ConstraintViolationException

Thrown after a hierarchical workflow completes when one or more roles listed in `HierarchicalConstraints.requiredWorkers` were never called by the Manager during the workflow execution.

```java
public class ConstraintViolationException extends AgentEnsembleException {

    /**
     * Human-readable descriptions of each constraint that was violated.
     * Each entry describes a single unfulfilled requirement (e.g.,
     * "Required worker 'QA Engineer' was never delegated a task").
     */
    private final List<String> violations;

    /**
     * Outputs from workers that did complete successfully before the
     * constraint check. Allows inspection of partial results.
     */
    private final List<TaskOutput> completedTaskOutputs;

    public ConstraintViolationException(List<String> violations) { ... }

    public ConstraintViolationException(List<String> violations,
            List<TaskOutput> completedTaskOutputs) { ... }

    public ConstraintViolationException(List<String> violations,
            Throwable cause) { ... }

    // Getters for all fields

    @Override
    public String getMessage() { ... }
}
```

**When thrown:**
- After the hierarchical workflow manager completes, when `HierarchicalConstraints.requiredWorkers` contains roles that were never called during the workflow execution

**getMessage() format:**
- Single violation: `"Hierarchical constraint violated: <violation description>"`
- Multiple violations: `"Hierarchical constraints violated (N): <v1>; <v2>; ..."`

**NOT thrown for pre-delegation constraint failures:**
Pre-delegation constraints (`allowedWorkers`, `maxCallsPerWorker`, `globalMaxDelegations`, `requiredStages`) are enforced before a delegation is approved and return a `DelegationPolicyResult.reject()` result back to the Manager LLM — they do not produce exceptions.

**Recovery:**
- `getViolations()` lists all unfulfilled requirements, identifying which required roles were skipped
- `getCompletedTaskOutputs()` provides partial results from workers that did execute

### AgentExecutionException

Thrown when the agent execution itself fails (LLM errors, infrastructure failures).

```java
public class AgentExecutionException extends AgentEnsembleException {

    /** Role of the agent that failed. */
    private final String agentRole;

    /** Description of the task the agent was working on. */
    private final String taskDescription;

    public AgentExecutionException(String message, String agentRole,
            String taskDescription, Throwable cause) { ... }

    // Getters
}
```

**When thrown:**
- LLM throws an exception (timeout, authentication failure, rate limiting, network error)
- LLM returns a response that LangChain4j cannot parse
- Any unexpected error during the agent's LLM interaction loop

**NOT thrown for:**
- Tool execution errors (these are caught and fed back to the LLM)
- Max iterations exceeded (has its own exception type)
- Empty LLM responses (these are logged as warnings, not errors)

### ToolExecutionException

Represents a tool infrastructure failure. This exception is primarily used for internal tracking and logging; it is NOT typically thrown to halt execution.

```java
public class ToolExecutionException extends AgentEnsembleException {

    /** Name of the tool that failed. */
    private final String toolName;

    /** Input that was passed to the tool. */
    private final String toolInput;

    public ToolExecutionException(String message, String toolName,
            String toolInput, Throwable cause) { ... }

    // Getters
}
```

**Behavior:**
- When a tool throws during execution, the error is caught and converted to an error message string
- The error message is fed back to the LLM as the tool's result
- The LLM can then decide to retry, use a different tool, or produce a final answer
- This allows graceful degradation rather than hard failure on tool errors

### MaxIterationsExceededException

Thrown when an agent exceeds its maximum tool call iterations without producing a final answer.

```java
public class MaxIterationsExceededException extends AgentEnsembleException {

    /** Role of the stuck agent. */
    private final String agentRole;

    /** Description of the task the agent was working on. */
    private final String taskDescription;

    /** The configured maximum. */
    private final int maxIterations;

    /** How many tool calls were actually made. */
    private final int toolCallsMade;

    public MaxIterationsExceededException(String agentRole,
            String taskDescription, int maxIterations, int toolCallsMade) { ... }

    // Getters
}
```

**When thrown:**
- Agent has made > `maxIterations` tool calls AND 3 "stop" messages have been sent to the LLM AND the LLM still hasn't produced a final text response

**Recovery:**
- User can increase `maxIterations` on the agent
- User can simplify the task description
- User can provide fewer/simpler tools

### PromptTemplateException

Thrown when template variable resolution fails.

```java
public class PromptTemplateException extends AgentEnsembleException {

    /** Variable names that were in the template but not in the inputs map. */
    private final List<String> missingVariables;

    /** The original template string (for debugging). */
    private final String template;

    public PromptTemplateException(String message,
            List<String> missingVariables, String template) { ... }

    // Getters
}
```

**When thrown:**
- `TemplateResolver.resolve()` finds `{variable}` placeholders with no matching key in the inputs map
- Always reports ALL missing variables, not just the first one

**Recovery:**
- User provides the missing variables in `ensemble.run(Map.of("var1", "value1", ...))`
- Or user removes the `{variable}` placeholders from the task description/expectedOutput

## Error Recovery Strategy Summary

| Exception | Where Thrown | Recovery |
|---|---|---|
| `ValidationException` | `build()` or `run()` | Fix configuration. No partial results. |
| `TaskExecutionException` | `WorkflowExecutor` | Inspect `completedTaskOutputs` for partial work. Fix cause. |
| `ConstraintViolationException` | `HierarchicalWorkflowExecutor` | Inspect `violations` to see which required roles were skipped. Inspect `completedTaskOutputs` for partial results. |
| `AgentExecutionException` | `AgentExecutor` | Wrapped in `TaskExecutionException`. Check LLM connectivity/credentials. |
| `ToolExecutionException` | Tool execution | NOT propagated (error fed to LLM). Fix tool implementation if persistent. |
| `MaxIterationsExceededException` | `AgentExecutor` | Increase `maxIterations`, simplify task, or reduce tools. |
| `PromptTemplateException` | Template resolution | Provide missing variables or fix template syntax. |

## Exception Flow Diagram

```
ensemble.run(inputs)
  |
  +-- ValidationException (config invalid)
  +-- PromptTemplateException (template vars missing)
  |
  +-- WorkflowExecutor.execute()
        |
        +-- TaskExecutionException (wraps below)
        |     |
        |     +-- AgentExecutionException (LLM failed)
        |     +-- MaxIterationsExceededException (agent stuck)
        |     |
        |     (Tool errors are NOT exceptions -- fed back to LLM)
        |
        +-- ConstraintViolationException (hierarchical: required workers never called)
              (pre-delegation policy violations are NOT exceptions -- DelegationPolicyResult.reject())
```
