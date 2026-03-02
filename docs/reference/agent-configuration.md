# Agent Configuration Reference

All fields available on `Agent.builder()`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `role` | `String` | Yes | -- | The agent's role/title. Used in prompts and logging. |
| `goal` | `String` | Yes | -- | The agent's primary objective. Included in the system prompt. |
| `background` | `String` | No | `null` | Persona context for the system prompt. Omitted when null or blank. |
| `tools` | `List<Object>` | No | `[]` | Tools available to this agent. Each entry must implement `AgentTool` or have `@Tool`-annotated methods. |
| `llm` | `ChatModel` | Yes | -- | Any LangChain4j `ChatModel`. |
| `allowDelegation` | `boolean` | No | `false` | When `true`, a `delegate` tool is auto-injected at execution time. |
| `verbose` | `boolean` | No | `false` | When `true`, prompts and LLM responses are logged at INFO level. |
| `maxIterations` | `int` | No | `25` | Maximum number of tool-call iterations before the agent is forced to produce a final answer. Must be greater than zero. |
| `responseFormat` | `String` | No | `""` | Extra formatting instructions appended to the system prompt. |

---

## Validation

The following validations are applied at `build()` time:

- `role` must not be null or blank
- `goal` must not be null or blank
- `llm` must not be null
- `maxIterations` must be greater than zero
- Each tool must implement `AgentTool` or have at least one `@Tool`-annotated method

---

## Example

```java
Agent agent = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, well-structured information on any topic")
    .background("You are a veteran researcher with 15 years of technology industry experience.")
    .tools(List.of(new WebSearchTool()))
    .llm(model)
    .allowDelegation(true)
    .verbose(false)
    .maxIterations(25)
    .responseFormat("Always structure your response with clear headings.")
    .build();
```

---

## Immutability

`Agent` is an immutable value object. Use `toBuilder()` to create modified copies:

```java
Agent verboseAgent = agent.toBuilder().verbose(true).build();
```
