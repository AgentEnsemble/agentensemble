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

## Logging

| Level | What |
|---|---|
| INFO | Tool call: `"{toolName}({truncatedInput}) -> {truncatedOutput}"` with duration |
| WARN | Tool error: `"{toolName}({truncatedInput}) -> Error: {message}"` |
| DEBUG | Full tool input and output (untruncated) |
| TRACE | Tool specification JSON sent to LLM |
