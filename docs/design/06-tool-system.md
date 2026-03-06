# 06 - Tool System

This document specifies the tool abstraction, adapter layer, and tool resolution logic.

## AgentTool Interface

The framework's tool interface. Users implement this to create custom tools.

```java
/**
 * A tool that an agent can use during task execution.
 * Implement this interface to create custom tools.
 *
 * Example:
 *   public class CalculatorTool implements AgentTool {
 *       public String name() { return "calculator"; }
 *       public String description() { return "Performs arithmetic. Input: a math expression like '2 + 3'."; }
 *       public ToolResult execute(String input) {
 *           double result = evaluate(input);
 *           return ToolResult.success(String.valueOf(result));
 *       }
 *   }
 */
public interface AgentTool {

    /**
     * Unique tool name. Used by the LLM to select this tool.
     * Must be non-null, non-blank, and contain only alphanumeric characters and underscores.
     */
    String name();

    /**
     * Description of what this tool does and when to use it.
     * Shown to the LLM in the tool specification. Should be clear and actionable.
     */
    String description();

    /**
     * Execute the tool with the given text input.
     *
     * @param input The input string from the LLM's tool call
     * @return ToolResult indicating success or failure with output text
     */
    ToolResult execute(String input);
}
```

## ToolResult

```java
@Value
public class ToolResult {

    /** The text output from the tool. Empty string if no output. */
    String output;

    /** Whether the tool execution succeeded. */
    boolean success;

    /** Error message when success is false. Null when success is true. */
    String errorMessage;

    /**
     * Create a successful result.
     * @param output The tool's text output
     * @return ToolResult with success=true
     */
    public static ToolResult success(String output) {
        return new ToolResult(output != null ? output : "", true, null);
    }

    /**
     * Create a failure result.
     * @param errorMessage Description of what went wrong
     * @return ToolResult with success=false
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult("", false, errorMessage);
    }
}
```

## Two Tool Paths

AgentEnsemble supports two ways to provide tools to an agent:

### Path 1: AgentTool Interface

Users implement the `AgentTool` interface. The framework adapts these to LangChain4j's tool model via `LangChain4jToolAdapter`.

```java
public class SearchTool implements AgentTool {
    public String name() { return "web_search"; }
    public String description() { return "Search the web. Input: search query string."; }
    public ToolResult execute(String input) {
        String results = performSearch(input);
        return ToolResult.success(results);
    }
}

var agent = Agent.builder()
    .role("Researcher")
    .goal("Find information")
    .tools(List.of(new SearchTool()))
    .llm(model)
    .build();
```

### Path 2: LangChain4j @Tool Annotations

Users can pass objects with `@dev.langchain4j.agent.tool.Tool` annotated methods directly. These are passed through to LangChain4j with zero adaptation.

```java
public class MathTools {
    @dev.langchain4j.agent.tool.Tool("Calculate a mathematical expression")
    public double calculate(String expression) {
        return evaluateExpression(expression);
    }
}

var agent = Agent.builder()
    .role("Analyst")
    .goal("Analyze data")
    .tools(List.of(new MathTools()))
    .llm(model)
    .build();
```

### Mixed Tools

Both types can be combined in a single agent's tool list:

```java
var agent = Agent.builder()
    .role("Researcher")
    .goal("Find and analyze information")
    .tools(List.of(new SearchTool(), new MathTools()))  // AgentTool + @Tool annotated
    .llm(model)
    .build();
```

## LangChain4jToolAdapter

Converts `AgentTool` instances into LangChain4j's tool execution model.

### Adaptation Logic

For each `AgentTool`:

1. Create a `ToolSpecification`:
   - `name`: `agentTool.name()`
   - `description`: `agentTool.description()`
   - `parameters`: Single parameter named `"input"` of type `STRING`, described as `"The input to pass to the tool"`

2. Create a tool executor function that:
   - Extracts the `"input"` parameter value from the tool execution request's arguments (JSON)
   - Calls `agentTool.execute(input)`
   - Returns the result string:
     - If `ToolResult.success()`: returns `result.output()`
     - If `!ToolResult.success()`: returns `"Error: " + result.errorMessage()`
   - If `execute()` throws an exception: catches it, logs WARN, returns `"Error: " + exception.getMessage()`
   - If `execute()` returns null: returns `""` (empty string, treated as success)

### Tool Specification JSON Structure

The generated spec for an `AgentTool` looks like:

```json
{
  "name": "web_search",
  "description": "Search the web. Input: search query string.",
  "parameters": {
    "type": "object",
    "properties": {
      "input": {
        "type": "string",
        "description": "The input to pass to the tool"
      }
    },
    "required": ["input"]
  }
}
```

## Tool Resolution Flow

Called by `AgentExecutor` when preparing to execute an agent with tools.

```
resolveTools(List<Object> tools) -> ToolResolution:

  agentTools = new ArrayList<AgentTool>()
  annotatedObjects = new ArrayList<Object>()

  FOR EACH tool IN tools:
    IF tool instanceof AgentTool:
      agentTools.add((AgentTool) tool)
    ELSE IF hasToolAnnotatedMethods(tool):
      annotatedObjects.add(tool)
    ELSE:
      throw ValidationException(
        "Tool object of type '" + tool.getClass().getName()
        + "' is neither an AgentTool nor has @Tool-annotated methods")

  // Check for name collisions across all tools
  allNames = new HashSet<String>()
  FOR EACH agentTool IN agentTools:
    IF !allNames.add(agentTool.name()):
      throw ValidationException("Duplicate tool name: '" + agentTool.name() + "'")

  FOR EACH annotatedObj IN annotatedObjects:
    FOR EACH method WITH @Tool annotation:
      toolName = method.getName()  // or annotation value
      IF !allNames.add(toolName):
        throw ValidationException("Duplicate tool name: '" + toolName + "'")

  // Build the resolution result
  adaptedSpecs = agentTools.stream()
    .map(LangChain4jToolAdapter::toSpecification)
    .toList()
  agentToolMap = agentTools.stream()
    .collect(Collectors.toMap(AgentTool::name, Function.identity()))

  RETURN new ToolResolution(annotatedObjects, adaptedSpecs, agentToolMap)
```

### hasToolAnnotatedMethods Utility

```
hasToolAnnotatedMethods(Object obj) -> boolean:
  FOR EACH method IN obj.getClass().getMethods():
    IF method has @dev.langchain4j.agent.tool.Tool annotation:
      RETURN true
  RETURN false
```

## Edge Cases

| Scenario | Behavior |
|---|---|
| Tool list is empty | Valid. Agent uses pure reasoning, no tool loop. |
| Tool name is blank | `ValidationException` at agent build time |
| Tool name has special characters | `ValidationException` at agent build time (must be alphanumeric + underscores) |
| Two tools with same name | `ValidationException` at tool resolution time |
| `execute()` throws RuntimeException | Caught, error message fed back to LLM as tool result. Logged as WARN. |
| `execute()` returns null | Treated as `ToolResult.success("")` |
| `execute()` returns `ToolResult.failure(msg)` | `"Error: {msg}"` fed back to LLM so it can adjust approach |
| Tool object has no @Tool methods and is not AgentTool | `ValidationException` with clear message |
| Tool input from LLM is empty or malformed JSON | Tool receives input as-is; implementation handles it. Errors caught and fed back. |

## Tool-Level Approval Gates

`AbstractAgentTool` subclasses can pause execution to request human approval before
performing a dangerous or irreversible action. This mechanism threads a `ReviewHandler`
from `ExecutionContext` through `ToolContext` into the tool.

### ReviewHandler Injection Path

```
ExecutionContext.reviewHandler()
  -> ToolResolver.resolve(tools, metrics, executor, reviewHandler)
      -> ToolContext.of(name, metrics, executor, reviewHandler)
          -> AbstractAgentTool.setContext(toolContext)
              -> AbstractAgentTool.rawReviewHandler()  (at execution time)
```

`ToolContext` stores the handler as `Object` (not `ReviewHandler`) to avoid forcing a class
load of `net.agentensemble.review.ReviewHandler` when the `agentensemble-review` module is
absent from the runtime classpath. The cast happens inside `requestApproval()` with a
`NoClassDefFoundError` guard.

This path is distinct from `HumanInputTool`, which receives its handler via
`SequentialWorkflowExecutor.injectReviewHandlerIntoTools()`. Both injection paths coexist:

| Path | Used by | How ReviewHandler arrives |
|------|---------|--------------------------|
| `ToolContext.reviewHandler` | All `AbstractAgentTool` subclasses | `ToolResolver` reads from `ExecutionContext` |
| `HumanInputTool.injectReviewHandler()` | `HumanInputTool` only | `SequentialWorkflowExecutor.injectReviewHandlerIntoTools()` |

### requestApproval() Contract

```
AbstractAgentTool.requestApproval(description) -> ReviewDecision:

  rawHandler = ToolContext.reviewHandler()  // Object, may be null

  IF rawHandler == null:
    RETURN ReviewDecision.continueExecution()  // auto-approve

  handler = (ReviewHandler) rawHandler       // cast; CompileOnly guard applied

  isConsole = handler instanceof ConsoleReviewHandler
  IF isConsole:
    CONSOLE_APPROVAL_LOCK.lock()             // serialize concurrent console reviews

  TRY:
    request = ReviewRequest.of(
      description, "", DURING_EXECUTION,
      Review.DEFAULT_TIMEOUT, Review.DEFAULT_ON_TIMEOUT, null)
    RETURN handler.review(request)
  FINALLY:
    IF isConsole: CONSOLE_APPROVAL_LOCK.unlock()
```

The `CONSOLE_APPROVAL_LOCK` is a `static final ReentrantLock` on `AbstractAgentTool`,
shared across all tool instances. It prevents interleaved console output when the agent
executor runs multiple tools concurrently in the same ReAct turn.

Non-console handlers (auto-approve, webhook) are not serialized.

### Exception Propagation

`AbstractAgentTool.execute()` re-throws both `ExitEarlyException` and `IllegalStateException`
instead of converting them to `ToolResult.failure()`:

- `ExitEarlyException`: reviewer stopped the pipeline (existing behaviour)
- `IllegalStateException`: configuration error (e.g., `requireApproval=true` with no handler)

`LangChain4jToolAdapter.executeForResult()` similarly re-throws `IllegalStateException`
so configuration errors propagate all the way up to the caller (wrapped in
`AgentExecutionException` -> `TaskExecutionException`).

### Built-in Tool Pattern

```
doExecute(String input):
  IF requireApproval:
    IF rawReviewHandler() == null:
      THROW IllegalStateException(
        "Tool '<name>' requires approval but no ReviewHandler is configured. ...")
    decision = requestApproval("<description>: " + input)
    IF decision instanceof ExitEarly:
      RETURN ToolResult.failure("Rejected by reviewer: " + input)
    IF decision instanceof Edit:
      input = edit.revisedOutput()   // use reviewer's replacement
    // Continue: proceed with original or revised input

  // ... actual tool work
```

---

## Tool Pipeline

`ToolPipeline` chains multiple `AgentTool` instances together into a single compound tool that
the LLM calls once. All steps execute sequentially inside a single `execute(String)` call with
no LLM round-trips between them. Full specification: [Design: Tool Pipeline](17-tool-pipeline.md).

### Class Structure

```
AbstractAgentTool
  ToolPipeline              -- chains List<PipelineStep> sequentially
  
PipelineErrorStrategy       -- FAIL_FAST | CONTINUE_ON_FAILURE
```

### Registration

`ToolPipeline implements AgentTool`. No changes to `ToolResolver`, `LangChain4jToolAdapter`,
or agent/task registration are needed. It is registered and adapted exactly like any other tool.

### Context Propagation

`ToolPipeline` overrides the package-private `setContext(ToolContext)` to propagate the injected
context to all nested steps that are `AbstractAgentTool` instances. Plain `AgentTool` steps
receive no injection.

### Error Strategies

| Strategy | On step failure |
|---|---|
| `FAIL_FAST` (default) | Return the failed step's `ToolResult` immediately; skip remaining steps |
| `CONTINUE_ON_FAILURE` | Forward the error message as the next step's input; run to completion |

### Step Adapters

An optional `Function<ToolResult, String>` adapter can be attached to any step via
`Builder.adapter()`. The adapter transforms that step's output before it is passed to the next
step. Adapters are only called when the step **succeeds** and only when there is a next step.

---

## Logging

| Level | What |
|---|---|
| INFO | Tool call: `"{toolName}({truncatedInput}) -> {truncatedOutput}"` with duration |
| WARN | Tool error: `"{toolName}({truncatedInput}) -> Error: {message}"` |
| ERROR | Tool configuration error (e.g., `IllegalStateException` from `requireApproval`) |
| DEBUG | Full tool input and output (untruncated) |
| TRACE | Tool specification JSON sent to LLM |
