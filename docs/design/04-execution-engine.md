# 04 - Execution Engine

This document details the execution flow from `Ensemble.run()` through to task completion.

## High-Level Flow

```
User calls ensemble.run(inputs)
  |
  v
[1. Validate Ensemble]
  - Check all validation rules (see 03-domain-model.md, Ensemble section)
  - Throws ValidationException on failure
  |
  v
[2. Resolve Templates]
  - For each task: resolve {variables} in description and expectedOutput
  - Uses TemplateResolver.resolve(template, inputs)
  - Creates new Task instances with resolved text (original tasks are immutable, unchanged)
  - Throws PromptTemplateException if any variables are missing from inputs
  |
  v
[3. Select WorkflowExecutor]
  - SEQUENTIAL -> SequentialWorkflowExecutor
  - HIERARCHICAL -> HierarchicalWorkflowExecutor
  |
  v
[4. Execute via WorkflowExecutor]
  - Returns EnsembleOutput
```

## SequentialWorkflowExecutor

### Interface

```java
public interface WorkflowExecutor {
    EnsembleOutput execute(List<Task> resolvedTasks, boolean verbose);
}
```

### Algorithm

```
Input: List<Task> resolvedTasks, boolean verbose
Output: EnsembleOutput

1.  ensembleStartTime = Instant.now()
2.  completedOutputs = new LinkedHashMap<Task, TaskOutput>()
3.  agentExecutor = new AgentExecutor()
4.  totalTasks = resolvedTasks.size()

5.  FOR EACH task IN resolvedTasks:
      a. taskIndex = indexOf(task) + 1

      b. MDC.put("task.index", taskIndex + "/" + totalTasks)
         MDC.put("task.description", truncate(task.description(), 80))
         MDC.put("agent.role", task.agent().role())

      c. LOG INFO: "Task {taskIndex}/{totalTasks} starting | Description: {truncated} | Agent: {role}"

      d. contextOutputs = task.context().stream()
           .map(ctxTask -> {
               TaskOutput output = completedOutputs.get(ctxTask);
               if (output == null) {
                   throw new TaskExecutionException(
                       "Context task not yet completed: " + ctxTask.description(),
                       ctxTask.description(), ctxTask.agent().role(),
                       List.copyOf(completedOutputs.values()));
               }
               return output;
           })
           .toList();

      e. LOG DEBUG: "Task {taskIndex}/{totalTasks} context: {contextOutputs.size()} prior outputs"

      f. TRY:
           taskOutput = agentExecutor.execute(task, contextOutputs, verbose)
         CATCH (AgentExecutionException | MaxIterationsExceededException e):
           throw new TaskExecutionException(
               "Task failed: " + task.description(),
               task.description(), task.agent().role(),
               List.copyOf(completedOutputs.values()), e)

      g. completedOutputs.put(task, taskOutput)

      h. LOG INFO: "Task {taskIndex}/{totalTasks} completed | Duration: {duration} | Tool calls: {count}"
         IF verbose:
           LOG INFO: "Task {taskIndex}/{totalTasks} output preview: {truncate(raw, 200)}"

      i. MDC.remove("task.index")
         MDC.remove("task.description")
         MDC.remove("agent.role")

6.  totalDuration = Duration.between(ensembleStartTime, Instant.now())
7.  allOutputs = List.copyOf(completedOutputs.values())
8.  finalOutput = allOutputs.getLast().raw()
9.  totalToolCalls = allOutputs.stream().mapToInt(TaskOutput::toolCallCount).sum()

10. RETURN EnsembleOutput.builder()
      .raw(finalOutput)
      .taskOutputs(allOutputs)
      .totalDuration(totalDuration)
      .totalToolCalls(totalToolCalls)
      .build()
```

### Error Behavior

- If a task fails midway through the sequence, execution stops immediately.
- `TaskExecutionException` is thrown carrying:
  - The failing task's description and agent role
  - All completed task outputs up to the point of failure (for partial recovery)
  - The original cause (AgentExecutionException, etc.)
- MDC is always cleaned up (even on failure) via try/finally.

---

## HierarchicalWorkflowExecutor

The `HierarchicalWorkflowExecutor` runs a manager agent that orchestrates worker agents via the
`DelegateTaskTool`. The manager receives a system prompt describing available workers and uses tool
calls to delegate subtasks. It produces a final synthesised answer once all delegations are
complete.

### Constraint Enforcement Wiring

When `Ensemble.hierarchicalConstraints` is non-null, the executor creates a
`HierarchicalConstraintEnforcer` and integrates it into the execution pipeline before the manager
agent starts running:

```
constraints != null
  |
  v
enforcer = new HierarchicalConstraintEnforcer(constraints)
  |
  +-- prepended as first DelegationPolicy in the policy chain
  |     (user-supplied policies still apply after the constraint checks)
  |
  +-- an internal EnsembleListener is added to ExecutionContext
        -> on DelegationCompletedEvent: enforcer.recordDelegation(event.workerRole())
```

This wiring ensures that every delegation the manager makes is (a) checked against the constraints
before the worker is invoked, and (b) recorded so that post-execution validation has an accurate
per-worker record of completed delegations (distinct from the *approved attempt* counts used for
enforcing caps at policy-evaluation time).

### Pre-Delegation Enforcement (inside DelegateTaskTool)

The `HierarchicalConstraintEnforcer` is the first `DelegationPolicy` evaluated on every
`DelegateTaskTool` invocation. Each check runs in order; the first failure short-circuits and
returns `DelegationPolicyResult.reject(reason)`, which causes `DelegateTaskTool` to return a
`DelegationResponse` with `DelegationStatus.FAILURE` back to the manager LLM without invoking the
worker:

| Order | Check | Rejection reason returned to LLM |
|-------|-------|-----------------------------------|
| 1 | **`allowedWorkers` check** — when `allowedWorkers` is non-empty, the target worker role must be present in the set | `"Agent '{role}' is not in the allowedWorkers list"` |
| 2 | **Global cap check** — when `globalMaxDelegations > 0`, the total number of approved delegation attempts (including attempts whose worker later fails) must be below the cap | `"Global delegation cap of {n} has been reached"` |
| 3 | **Per-worker cap check** — when `maxCallsPerWorker` contains an entry for the target role, the per-worker approved attempt count (including attempts whose worker later fails) must be below that cap | `"Agent '{role}' has reached its delegation cap of {n}"` |
| 4 | **`requiredStages` ordering check** — when `requiredStages` is non-empty, the target worker must belong to the current or an earlier stage (all agents in every prior stage must have been called at least once before the next stage is accessible) | `"Cannot delegate to '{role}': stage {n} is not yet complete"` |

All four checks return `DelegationPolicyResult.reject(reason)` on failure. If all checks pass, the
enforcer returns `DelegationPolicyResult.allow()` and remaining user-defined policies are evaluated
in order.

### Post-Execution Validation

After the manager agent finishes and produces its final text response,
`enforcer.validatePostExecution(completedTaskOutputs)` is called with the list of all `TaskOutput`
objects collected during the run. This method checks that every role in
`HierarchicalConstraints.requiredWorkers` received at least one successful delegation. If any
required worker was never called, a `ConstraintViolationException` is thrown carrying:

- The list of worker roles that were required but not called
- The partial `List<TaskOutput>` collected up to the point of failure (for diagnostics)

```
manager finishes
  |
  v
enforcer.validatePostExecution(completedTaskOutputs)
  |
  +-- for each role in requiredWorkers:
  |     IF recordDelegation() was never called for that role -> violation
  |
  +-- no violations -> execution continues normally; EnsembleOutput returned
  |
  +-- violations exist -> throw ConstraintViolationException(violations, completedTaskOutputs)
```

### Algorithm Summary

```
Input: List<Task> resolvedTasks, ExecutionContext ctx
Output: EnsembleOutput

1.  IF ensemble.hierarchicalConstraints() != null:
      enforcer = new HierarchicalConstraintEnforcer(ensemble.hierarchicalConstraints())
      policies = [enforcer] + ensemble.delegationPolicies()   // enforcer is FIRST
      ctx = ctx.withAdditionalListener(
                  new DelegationRecordingListener(enforcer))   // records on DelegationCompletedEvent
    ELSE:
      policies = ensemble.delegationPolicies()

2.  Build DelegationContext:
      delegationCtx = DelegationContext.builder()
        .workerAgents(workerAgents)
        .executionContext(ctx)
        .policies(policies)
        .maxDepth(ensemble.maxDelegationDepth())
        .build()

3.  Execute manager task via AgentExecutor (with DelegateTaskTool in tool list):
      TRY:
        managerOutput = agentExecutor.execute(managerTask, resolvedTasks, ctx, delegationCtx)
      CATCH (AgentExecutionException | MaxIterationsExceededException e):
        throw new TaskExecutionException(...)

4.  IF enforcer != null:
      enforcer.validatePostExecution(List.copyOf(completedOutputs.values()))
      // Throws ConstraintViolationException if any requiredWorkers were not called

5.  RETURN EnsembleOutput built from managerOutput and all delegated TaskOutputs
```

### Error Behavior

| Scenario | Behavior |
|---|---|
| Delegation to disallowed worker | `DelegationPolicyResult.reject` from enforcer; worker not invoked; failure message returned to manager LLM |
| Global delegation cap exceeded | `DelegationPolicyResult.reject` from enforcer; manager LLM is told the cap was reached |
| Per-worker cap exceeded | `DelegationPolicyResult.reject` from enforcer; manager LLM is told that specific worker is capped |
| Stage ordering violated | `DelegationPolicyResult.reject` from enforcer; manager LLM is told the prior stage is incomplete |
| Required worker never called | `ConstraintViolationException` thrown after manager finishes; carries violation list and partial outputs |
| Manager LLM error | Wrapped in `AgentExecutionException`; `ConstraintViolationException` is not raised (manager didn't finish) |

---

## AgentExecutor

The core component that runs a single agent on a single task.

### Interface

```java
public class AgentExecutor {
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, boolean verbose) { ... }
}
```

### Algorithm

```
Input: Task task, List<TaskOutput> contextOutputs, boolean verbose
Output: TaskOutput

1.  taskStartTime = Instant.now()
2.  agent = task.agent()
3.  effectiveVerbose = verbose || agent.verbose()

4.  systemMessage = AgentPromptBuilder.buildSystemPrompt(agent)
5.  userMessage = AgentPromptBuilder.buildUserPrompt(task, contextOutputs)

6.  LOG DEBUG: "System prompt ({length} chars):\n{systemMessage}"
    LOG DEBUG: "User prompt ({length} chars):\n{userMessage}"
    IF effectiveVerbose:
      LOG INFO: "System prompt:\n{systemMessage}"
      LOG INFO: "User prompt:\n{userMessage}"

7.  resolvedTools = resolveTools(agent.tools())
    // See 06-tool-system.md for tool resolution details

8.  toolCallCounter = new AtomicInteger(0)
    maxIterations = agent.maxIterations()
    stopMessageCount = 0

9.  IF resolvedTools is non-empty (agent has tools):

      a. Construct message list:
         messages = [SystemMessage(systemMessage), UserMessage(userMessage)]

      b. Collect all tool specifications from resolved tools

      c. LOOP:
           response = agent.llm().generate(messages, toolSpecifications)
           aiMessage = response.content()
           messages.add(aiMessage)

           IF aiMessage has tool execution requests:
             FOR EACH toolRequest IN aiMessage.toolExecutionRequests():
               toolCallCounter.incrementAndGet()

               IF toolCallCounter.get() > maxIterations:
                 stopMessageCount++
                 stopText = "STOP: Maximum tool iterations (" + maxIterations
                   + ") reached. You must provide your best final answer now "
                   + "based on information gathered so far."

                 IF stopMessageCount >= 3:
                   throw new MaxIterationsExceededException(
                     agent.role(), task.description(),
                     maxIterations, toolCallCounter.get())

                 messages.add(ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), stopText))
                 LOG WARN: "Agent '{role}' exceeded max iterations ({max}). Stop message sent ({count}/3)."

               ELSE:
                 TRY:
                   toolResult = executeTool(toolRequest, resolvedTools)
                   messages.add(ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), toolResult))
                   LOG INFO: "Tool call: {toolName}({truncatedInput}) -> {truncatedOutput}"
                 CATCH (Exception e):
                   errorMsg = "Tool error: " + e.getMessage()
                   messages.add(ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), errorMsg))
                   LOG WARN: "Tool error: {toolName}({truncatedInput}) -> {errorMsg}"

             CONTINUE LOOP

           ELSE:
             // LLM produced a text response (no more tool calls)
             finalResponse = aiMessage.text()
             BREAK LOOP

    ELSE (no tools):

      a. response = agent.llm().generate(
           List.of(SystemMessage(systemMessage), UserMessage(userMessage)))
      b. finalResponse = response.content().text()
      c. LOG DEBUG: "Agent '{role}' completed (no tools)"

10. IF finalResponse is null or blank:
      LOG WARN: "Agent '{role}' returned empty response for task '{truncatedDescription}'"
      finalResponse = ""

11. LOG TRACE: "Full agent response:\n{finalResponse}"
    IF effectiveVerbose:
      LOG INFO: "Agent response:\n{finalResponse}"

12. duration = Duration.between(taskStartTime, Instant.now())

13. RETURN TaskOutput.builder()
      .raw(finalResponse)
      .taskDescription(task.description())
      .agentRole(agent.role())
      .completedAt(Instant.now())
      .duration(duration)
      .toolCallCount(toolCallCounter.get())
      .build()
```

### Error Handling Matrix

| Scenario | Behavior |
|---|---|
| LLM throws exception (timeout, auth, rate limit) | Wrapped in `AgentExecutionException` with agent role, task description, original cause |
| Tool throws exception during execution | Caught, converted to error string: `"Tool error: {message}"`. Passed back to LLM so it can retry or adjust. Logged as WARN. |
| Tool returns null | Treated as empty success result (`""`) |
| LLM returns null/empty text | Logged as WARN, treated as empty string output |
| Max iterations hit | Tool wrapper returns stop message. After 3 stop messages without the LLM producing a final answer, `MaxIterationsExceededException` is thrown. |
| LLM returns malformed tool call | LangChain4j handles parsing; if it throws, wrapped in `AgentExecutionException` |
| Tool call with empty/malformed input | Tool receives the input as-is; tool implementation decides how to handle. Errors caught and fed back to LLM. |

### Tool Execution

```
executeTool(ToolExecutionRequest request, ToolResolution resolvedTools):
  toolName = request.name()
  toolInput = request.arguments()

  // Check AgentTool map first
  agentTool = resolvedTools.agentToolMap().get(toolName)
  if agentTool != null:
    result = agentTool.execute(toolInput)
    if result == null:
      return ""
    if result.success():
      return result.output()
    else:
      return "Error: " + result.errorMessage()

  // Otherwise, delegate to LangChain4j annotated tool execution
  return resolvedTools.executeAnnotatedTool(toolName, request)
```

---

## Trace Accumulation and Metrics (issue #42)

Every `AgentExecutor.execute()` call creates a `TaskTraceAccumulator` at the start and freezes it
into an immutable `TaskTrace` at the end. The accumulator collects:

- **Prompts**: system and user prompt text, build time
- **LLM interactions**: one record per `ChatModel.chat()` call with tokens, latency, and tool results
- **Tool calls**: name, arguments, result, timing, outcome per tool invocation
- **Delegations**: full worker trace captured for peer delegations via `AgentDelegationTool`
- **Memory operations**: STM writes, LTM stores and retrievals, entity lookups (wired at STANDARD+)

`TaskTrace` is attached to `TaskOutput`. `Ensemble.runWithInputs()` collects all task traces and
assembles an `ExecutionTrace` at the run level which is then attached to `EnsembleOutput`.

`ExecutionMetrics` and `TaskMetrics` are derived from the accumulated data, providing aggregated
token counts, latencies, and optional monetary cost estimates.

### Trace Export

Traces can be exported after each run via `Ensemble.builder().traceExporter(exporter)`. The built-in
`JsonTraceExporter` writes pretty-printed JSON to a directory (one file per run) or a fixed file.
Custom exporters implement `ExecutionTraceExporter` -- a single-method interface that receives the
complete `ExecutionTrace` after each successful run.

When `CaptureMode.FULL` is active and no explicit `traceExporter` is configured, a
`JsonTraceExporter` writing to `./traces/` is auto-registered.

---

## CaptureMode (issue #89)

`CaptureMode` is an opt-in, zero-configuration toggle that controls how much data the framework
records during execution. It is set on `Ensemble.builder().captureMode()` or via the
`agentensemble.captureMode` system property / `AGENTENSEMBLE_CAPTURE_MODE` environment variable.

### Resolution order (first wins)

1. `.captureMode(CaptureMode.STANDARD)` on the builder
2. `-Dagentensemble.captureMode=STANDARD` JVM system property
3. `AGENTENSEMBLE_CAPTURE_MODE=STANDARD` environment variable
4. `CaptureMode.OFF` (default)

### What each level adds

| Level | Additional data captured |
|---|---|
| `OFF` | Base trace: prompts, tool args/results, timing, token counts |
| `STANDARD` | Full LLM message history per ReAct iteration (`LlmInteraction.messages`); memory operation counts wired into `MemoryOperationCounts` |
| `FULL` | Everything in STANDARD; auto-export to `./traces/`; enriched tool I/O (`ToolCallTrace.parsedInput`) |

### Implementation inside AgentExecutor

At the start of each task execution:
- The `TaskTraceAccumulator` is created with the effective `CaptureMode`.
- When `captureMode >= STANDARD`, a `MemoryOperationListener` is registered on `MemoryContext` to
  forward STM/LTM/entity events directly to the accumulator's increment methods.

During the ReAct loop (in `executeWithTools`):
- After each `ChatModel.chat()` call and before `finalizeIteration()`, when `captureMode >= STANDARD`,
  the current `messages` list is snapshotted via `CapturedMessage.fromAll(messages)` and stored on
  the accumulator.
- When `captureMode >= FULL`, the tool JSON arguments are parsed into a `Map<String,Object>` and
  set as `parsedInput` on each `ToolCallTrace`.

At task completion, the memory listener is always removed in a `finally` block to prevent listener
leakage across tasks.
