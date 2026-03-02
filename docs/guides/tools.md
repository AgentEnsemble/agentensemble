# Tools

Agents can be equipped with tools that they invoke during execution using a ReAct-style reasoning loop. There are two supported tool patterns.

---

## How Tools Work

When an agent has tools, the execution loop works as follows:

1. The agent receives a system prompt (role, goal, background) and user prompt (task description, context)
2. The agent decides whether to call a tool or produce a final answer
3. If a tool is called, the framework executes it and returns the result to the agent
4. The agent incorporates the tool result and decides on the next step (another tool call or a final answer)
5. This loop continues until the agent produces a final text answer or `maxIterations` is reached

---

## Option 1: Implement `AgentTool`

The `AgentTool` interface provides a simple, string-in / string-out contract:

```java
public interface AgentTool {
    String name();
    String description();
    ToolResult execute(String input);
}
```

### Example: Web Search Tool

```java
public class WebSearchTool implements AgentTool {

    private final SearchClient client;

    public WebSearchTool(SearchClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "Search the web for current information. " +
               "Input: a search query string. " +
               "Returns: search results as text.";
    }

    @Override
    public ToolResult execute(String input) {
        try {
            String results = client.search(input);
            return ToolResult.success(results);
        } catch (Exception e) {
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }
}
```

### `ToolResult`

Use `ToolResult.success(content)` for successful results and `ToolResult.failure(reason)` for errors. The failure message is returned to the agent so it can decide how to proceed.

```java
ToolResult.success("Found 3 results: ...");
ToolResult.failure("API rate limit exceeded. Try again later.");
```

### Registering an `AgentTool`

```java
Agent agent = Agent.builder()
    .role("Researcher")
    .goal("Find current information")
    .tools(List.of(new WebSearchTool(searchClient)))
    .llm(model)
    .build();
```

---

## Option 2: LangChain4j `@Tool` Annotation

Objects with `@Tool`-annotated methods are supported directly. The method signature is used to generate the tool specification. Multi-parameter methods are supported (the `-parameters` compiler flag is required; the framework's `build.gradle.kts` already enables this).

### Example: Math and Lookup Tools

```java
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

public class DataTools {

    @Tool("Calculate the percentage change between two values. " +
          "Use this to compute growth rates.")
    public double percentageChange(
            @P("The original value") double original,
            @P("The new value") double newValue) {
        return ((newValue - original) / original) * 100.0;
    }

    @Tool("Look up the current stock price for a ticker symbol. " +
          "Input: a stock ticker symbol like 'AAPL'.")
    public String stockPrice(String ticker) {
        return fetchStockPrice(ticker);
    }
}
```

### Registering `@Tool`-Annotated Objects

```java
Agent agent = Agent.builder()
    .role("Financial Analyst")
    .goal("Analyse financial data using available tools")
    .tools(List.of(new DataTools()))
    .llm(model)
    .build();
```

---

## Combining Both Tool Types

Both types can be used together in a single agent:

```java
Agent agent = Agent.builder()
    .role("Research Analyst")
    .goal("Find and analyse data")
    .tools(List.of(
        new WebSearchTool(searchClient),   // AgentTool
        new DataTools()                     // @Tool annotated
    ))
    .llm(model)
    .build();
```

---

## Writing Good Tool Descriptions

The description is critical -- it is the only information the LLM uses to decide when and how to call the tool.

**Best practices:**

1. Start with a verb that describes what the tool does: `"Search..."`, `"Calculate..."`, `"Look up..."`, `"Convert..."`.
2. Describe the input format clearly: `"Input: a search query string"`, `"Input: a company name"`.
3. Describe the output: `"Returns: a JSON object with price and volume"`.
4. List any constraints: `"Only use this for publicly traded companies"`.

**Good description:**
```
"Search the web for recent news and articles. Input: a concise search query of 2-8 words. Returns: a summary of the top results including source names and publication dates."
```

**Poor description:**
```
"Web search tool"
```

---

## Tool Parameter Annotations (`@P`)

For `@Tool` annotated methods with multiple parameters, use `@P` to describe each parameter:

```java
@Tool("Calculate compound interest.")
public double compoundInterest(
        @P("Principal amount in dollars") double principal,
        @P("Annual interest rate as a decimal, e.g. 0.05 for 5%") double rate,
        @P("Number of years") int years) {
    return principal * Math.pow(1 + rate, years);
}
```

---

## Max Iterations

When an agent has tools, the `maxIterations` field limits the number of tool calls. The default is 25. Set it lower for tasks where you want the agent to be concise, or higher for complex analytical tasks:

```java
Agent agent = Agent.builder()
    .role("Data Analyst")
    .goal("Perform thorough data analysis")
    .tools(List.of(new DataTools()))
    .llm(model)
    .maxIterations(50)
    .build();
```

---

## Delegation Tool

When `allowDelegation = true`, an additional `delegate` tool is automatically injected at execution time. This is distinct from the tools you configure on the agent. See the [Delegation guide](delegation.md).
