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
  - HIERARCHICAL -> throws UnsupportedOperationException("Hierarchical workflow not yet implemented")
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
