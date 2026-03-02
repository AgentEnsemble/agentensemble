# Agents

An agent is an AI entity with a defined role and goal. Each agent uses a LangChain4j `ChatModel` to reason and produce outputs. Agents are immutable value objects built via a fluent builder.

---

## Creating an Agent

```java
Agent researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, well-structured information on any given topic")
    .background("You are a veteran researcher with 15 years of industry experience.")
    .llm(model)
    .build();
```

The `role`, `goal`, and `llm` fields are required. All other fields are optional.

---

## Role and Goal

The `role` and `goal` fields are the most important parts of the agent identity. They are combined into the system prompt that the agent receives before each task.

- **role**: The agent's title. Keep it concise and descriptive. Example: `"Data Scientist"`, `"Legal Reviewer"`, `"Code Reviewer"`.
- **goal**: The agent's primary objective. Write this as a directive. Example: `"Analyse data and identify statistical patterns"`.

---

## Background

The `background` field adds persona context to the system prompt. Use it to give the agent domain expertise, a specific point of view, or constraints:

```java
Agent analyst = Agent.builder()
    .role("Financial Analyst")
    .goal("Evaluate investment opportunities and risks")
    .background("You specialise in technology sector equities. Your analysis is always " +
                "grounded in publicly available data. You never speculate beyond the evidence.")
    .llm(model)
    .build();
```

When `background` is omitted, only role and goal are included in the system prompt.

---

## Tools

Agents can be equipped with tools that they call during their ReAct-style reasoning loop. There are two ways to provide tools:

### Option 1: Implement `AgentTool`

```java
public class WebSearchTool implements AgentTool {
    public String name() { return "web_search"; }
    public String description() { return "Search the web for current information. Input: a search query."; }
    public ToolResult execute(String input) {
        String results = performSearch(input);
        return ToolResult.success(results);
    }
}

Agent agent = Agent.builder()
    .role("Researcher")
    .goal("Find current information")
    .tools(List.of(new WebSearchTool()))
    .llm(model)
    .build();
```

### Option 2: LangChain4j `@Tool` Annotation

```java
public class MathTools {
    @Tool("Calculate a mathematical expression. Input: an expression like '2 + 3 * 4'.")
    public double calculate(String expression) {
        return evaluateExpression(expression);
    }

    @Tool("Convert a temperature from Celsius to Fahrenheit. Input: temperature in Celsius as a number.")
    public double celsiusToFahrenheit(double celsius) {
        return celsius * 9.0 / 5.0 + 32;
    }
}

Agent agent = Agent.builder()
    .role("Analyst")
    .goal("Perform calculations and data conversions")
    .tools(List.of(new MathTools()))
    .llm(model)
    .build();
```

Both tool types can be combined in the same list:

```java
Agent agent = Agent.builder()
    .role("Research Analyst")
    .goal("Research and calculate")
    .tools(List.of(new WebSearchTool(), new MathTools()))
    .llm(model)
    .build();
```

See the [Tools guide](tools.md) for more detail.

---

## Verbose Mode

When `verbose = true`, the agent's system prompt, user prompt, and LLM response are logged at INFO level. This is useful for debugging:

```java
Agent agent = Agent.builder()
    .role("Researcher")
    .goal("Research topics")
    .llm(model)
    .verbose(true)
    .build();
```

Verbose can also be set at the ensemble level, which applies to all agents:

```java
Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .tasks(...)
    .verbose(true)
    .build();
```

---

## Max Iterations

The `maxIterations` field limits how many tool calls an agent can make before being forced to produce a final answer. The default is 25.

```java
Agent agent = Agent.builder()
    .role("Data Analyst")
    .goal("Analyse datasets")
    .llm(model)
    .maxIterations(10)  // stop after 10 tool calls
    .build();
```

When the limit is exceeded, the agent receives a stop message asking it to produce a final answer with the information gathered so far. After three stop messages, a `MaxIterationsExceededException` is thrown.

---

## Response Format

Use `responseFormat` to add formatting instructions to the system prompt:

```java
Agent agent = Agent.builder()
    .role("Report Writer")
    .goal("Write structured reports")
    .llm(model)
    .responseFormat("Always respond in JSON. Use the schema: {\"title\": string, \"summary\": string, \"recommendations\": [string]}.")
    .build();
```

---

## Delegation

When `allowDelegation = true`, the agent has access to a `delegate` tool that it can call to hand off subtasks to other agents in the ensemble:

```java
Agent leadResearcher = Agent.builder()
    .role("Lead Researcher")
    .goal("Coordinate research by delegating specialised subtasks")
    .llm(model)
    .allowDelegation(true)
    .build();
```

The agent can then call `delegate("Writer", "Write an executive summary based on: ...")` during its reasoning loop.

See the [Delegation guide](delegation.md) for full details.

---

## Agent as a Value Object

Agents are immutable. If you need to create a variant, use `toBuilder()`:

```java
Agent verboseResearcher = researcher.toBuilder()
    .verbose(true)
    .build();
```

---

## Reference

See the [Agent Configuration reference](../reference/agent-configuration.md) for the complete field table with types and defaults.
