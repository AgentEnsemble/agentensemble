# Tools

Agents can be equipped with tools that they invoke during execution using a ReAct-style
reasoning loop. There are three supported tool patterns.

---

## How Tools Work

When an agent has tools, the execution loop works as follows:

1. The agent receives a system prompt (role, goal, background) and user prompt (task description, context)
2. The agent decides whether to call a tool or produce a final answer
3. If a tool is called, the framework executes it and returns the result to the agent
4. The agent incorporates the tool result and decides on the next step (another tool call or a final answer)
5. This loop continues until the agent produces a final text answer or `maxIterations` is reached

**Parallel execution:** When the LLM requests multiple tools in a single turn, AgentEnsemble
executes them concurrently using Java 21 virtual threads. Single tool calls are executed
directly without async overhead.

---

## Option 1: Extend `AbstractAgentTool` (Recommended)

`AbstractAgentTool` is the recommended base class. It provides:

- **Automatic metrics** -- timing, success/failure/error counters tagged by `(tool_name, agent_role)`
- **Structured logging** -- SLF4J logger pre-scoped to the tool name (`net.agentensemble.tool.<name>`)
- **Exception safety** -- any uncaught exception from `doExecute()` is caught, logged, and converted to `ToolResult.failure()`
- **Executor access** -- the framework tool executor for scheduling sub-tasks

Override `doExecute(String input)` instead of `execute(String input)`:

```java
public class TranslationTool extends AbstractAgentTool {

    private final TranslationClient client;

    public TranslationTool(TranslationClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "translate";
    }

    @Override
    public String description() {
        return "Translates text. Input format: '<target_language>: <text to translate>'.";
    }

    @Override
    protected ToolResult doExecute(String input) {
        if (input == null || !input.contains(":")) {
            return ToolResult.failure("Input must be in format 'language: text'");
        }
        String[] parts = input.split(":", 2);
        String targetLang = parts[0].trim();
        String text = parts[1].trim();

        log().debug("Translating {} chars to {}", text.length(), targetLang);

        String translated = client.translate(text, targetLang);
        metrics().incrementCounter("translations.completed", name(),
            Map.of("target_lang", targetLang));

        return ToolResult.success(translated);
    }
}
```

### Structured Output

Tools can return typed structured output alongside the plain-text response for the LLM:

```java
record SearchResult(String url, String title, String snippet) {}

@Override
protected ToolResult doExecute(String input) {
    List<SearchResult> results = searchEngine.query(input);
    String formatted = formatForLlm(results);
    // Structured payload available to listeners via ToolCallEvent.structuredResult()
    return ToolResult.success(formatted, results);
}
```

---

## Option 2: Implement `AgentTool` Directly

The `AgentTool` interface provides the minimal contract for simple tools:

```java
public interface AgentTool {
    String name();
    String description();
    ToolResult execute(String input);
}
```

Use this approach for the simplest cases where you don't need metrics, structured logging,
or automatic exception handling:

```java
public class UpperCaseTool implements AgentTool {

    @Override
    public String name() {
        return "uppercase";
    }

    @Override
    public String description() {
        return "Converts text to uppercase. Input: any text string.";
    }

    @Override
    public ToolResult execute(String input) {
        if (input == null) {
            return ToolResult.failure("Input must not be null");
        }
        return ToolResult.success(input.toUpperCase());
    }
}
```

---

## Option 3: Use `@Tool`-Annotated Methods

Register a plain Java object with methods annotated with `@dev.langchain4j.agent.tool.Tool`.
This is useful for tools with multiple methods or when integrating with existing LangChain4j code.

```java
public class DateUtils {

    @Tool("Returns the current date in yyyy-MM-dd format")
    public String today() {
        return LocalDate.now().toString();
    }

    @Tool("Adds the specified number of days to a date (format: yyyy-MM-dd)")
    public String addDays(
            @P("the starting date in yyyy-MM-dd format") String date,
            @P("number of days to add") int days) {
        return LocalDate.parse(date).plusDays(days).toString();
    }
}
```

Both tool types can be mixed freely:

```java
Agent.builder()
    .role("Scheduler")
    .tools(List.of(
        new TranslationTool(client),    // AbstractAgentTool
        new DateUtils()                  // @Tool-annotated
    ))
    .llm(chatModel)
    .build();
```

---

## ToolResult

`ToolResult` is the return type for all tools. Use the factory methods:

```java
// Successful result with plain text
ToolResult.success("The capital of France is Paris");

// Successful result with typed structured payload for listeners
ToolResult.success("Found 3 results", myStructuredObject);

// Failure result
ToolResult.failure("Could not connect to the database");
```

---

## Tool Execution Context

`AbstractAgentTool` provides three context accessors available in `doExecute()`:

| Accessor       | Type           | Description                                              |
|----------------|----------------|----------------------------------------------------------|
| `log()`        | `Logger`       | SLF4J logger named `net.agentensemble.tool.<toolName>`   |
| `metrics()`    | `ToolMetrics`  | Metrics backend for custom measurements                  |
| `executor()`   | `Executor`     | Framework tool executor (virtual threads by default)     |

These are safe to call even without the framework injecting a `ToolContext` (e.g., in unit tests).
Before injection, sensible defaults are used (class-level logger, no-op metrics, virtual thread executor).

---

## Thread Safety

Tool instances may be called concurrently from multiple virtual threads when:
- The agent uses parallel workflows
- The LLM requests multiple tools in a single turn

Tool implementations must be **thread-safe**. Prefer immutable state and local variables in
`doExecute()`. Shared state requires synchronization.

---

## Remote Tools

For tools implemented in Python, Node.js, or any other language, see:

- [Remote Tools](remote-tools.md) -- `ProcessAgentTool` and `HttpAgentTool`
- [Built-in Tools](built-in-tools.md) -- ready-to-use tool library

---

## Configuring Tool Execution

Configure tool execution at the Ensemble level:

```java
Ensemble.builder()
    .agent(agent)
    .task(task)
    // Virtual threads by default -- optimal for I/O-bound tools
    .toolExecutor(Executors.newVirtualThreadPerTaskExecutor())
    // Bounded pool for rate-limited APIs
    // .toolExecutor(Executors.newFixedThreadPool(4))
    // Pluggable metrics backend
    .toolMetrics(new MicrometerToolMetrics(registry))
    .build()
    .run();
```

See [Metrics](metrics.md) for full details on observability.
