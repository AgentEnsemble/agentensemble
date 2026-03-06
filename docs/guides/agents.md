# Agents

In v2, agents are **optional**. When a task has no explicit agent, the framework synthesizes
one automatically from the task description. You only need to declare an agent explicitly
when you want full control over the persona (role, background, verbose logging, etc.).

For most use cases, rely on the zero-ceremony API and let the framework do the rest.
See [AgentSynthesizer](#agentsynthesizer) below for details on how synthesis works.

---

## Creating an Agent (explicit, power-user)

```java
Agent researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, well-structured information on any given topic")
    .background("You are a veteran researcher with 15 years of industry experience.")
    .llm(model)
    .build();
```

The `role`, `goal`, and `llm` fields are required. All other fields are optional.

Bind the agent to a specific task:

```java
Task task = Task.builder()
    .description("Research the latest developments in AI")
    .expectedOutput("A 400-word summary")
    .agent(researcher)   // explicit agent
    .build();
```

---

## Role and Goal

The `role` and `goal` fields are the most important parts of the agent identity. They are
combined into the system prompt that the agent receives before each task.

- **role**: The agent's title. Keep it concise and descriptive.
  Example: `"Data Scientist"`, `"Legal Reviewer"`, `"Code Reviewer"`.
- **goal**: The agent's primary objective. Write this as a directive.
  Example: `"Analyse data and identify statistical patterns"`.

---

## Background

The `background` field adds context to the system prompt, giving the agent a persona and
relevant domain knowledge.

```java
Agent analyst = Agent.builder()
    .role("Financial Analyst")
    .goal("Identify investment opportunities from market data")
    .background("You are a CFA-certified financial analyst with expertise in tech sector equities.")
    .llm(model)
    .build();
```

---

## Tools

The `tools` field registers tools available during the ReAct loop. Each entry must be either:

- An `AgentTool` instance (implements `AgentTool` interface)
- An object with `@Tool`-annotated methods

```java
Agent researcher = Agent.builder()
    .role("Researcher")
    .goal("Find up-to-date information using web search")
    .llm(model)
    .tools(List.of(new WebSearchTool(), new CalculatorTool()))
    .build();
```

---

## Other Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `maxIterations` | `int` | `25` | Maximum tool-call iterations before forcing a final answer |
| `allowDelegation` | `boolean` | `false` | Whether this agent may delegate to other agents |
| `verbose` | `boolean` | `false` | Logs prompts and responses at INFO level |
| `responseFormat` | `String` | `""` | Formatting instruction appended to the system prompt |

---

## AgentSynthesizer

When a task has no explicit agent, the framework auto-synthesizes one using the ensemble's
configured `AgentSynthesizer`. The synthesized agent is ephemeral -- it exists only for
the duration of that task execution.

### Template-based synthesis (default)

The default synthesizer extracts a role noun from the task description using a verb-to-role
lookup table. No extra LLM call is made:

| First word | Synthesized role |
|---|---|
| Research / Investigate | Researcher |
| Write / Draft / Compose | Writer |
| Analyze / Analyse / Evaluate | Analyst |
| Design | Designer |
| Build / Implement / Develop | Developer |
| Test / Verify | Tester |
| Summarize / Summarise | Summarizer |
| Review | Reviewer |
| Plan | Planner |
| (anything else) | Agent |

The goal is set to the full task description. The backstory is derived from the role.

```java
// "Research AI trends" -> role "Researcher", goal "Research AI trends"
Task task = Task.of("Research AI trends and summarise findings");
```

### LLM-based synthesis (opt-in)

For higher-quality personas, use `AgentSynthesizer.llmBased()`. This makes one additional
LLM call per agentless task to generate a tailored role, goal, and backstory:

```java
Ensemble.builder()
    .chatLanguageModel(model)
    .agentSynthesizer(AgentSynthesizer.llmBased())
    .task(Task.of("Analyse the quarterly earnings report"))
    .build()
    .run();
```

!!! warning "Model requirements"
    `AgentSynthesizer.llmBased()` calls `ChatModel.chat(ChatRequest)` during
    `Ensemble.resolveAgents()`, **before any task executes**. The configured `ChatModel`
    must properly implement `chat(ChatRequest)` or `doChat(ChatRequest)` (the LangChain4j
    1.x override point). A model that does not implement either will throw
    `RuntimeException: Not implemented` at synthesis time.

    For tests that use a stub or fake `ChatModel`, **prefer the default `AgentSynthesizer.template()`**
    (no LLM call during synthesis) or ensure the stub returns valid JSON persona responses.
    See the [Testing guide](testing.md#agent-synthesis-and-stub-models) for patterns.

### Custom synthesizer

Implement the `AgentSynthesizer` interface to provide your own strategy:

```java
AgentSynthesizer customSynthesizer = (task, ctx) -> Agent.builder()
    .role("Domain Expert")
    .goal(task.getDescription())
    .background("You are an expert in this domain.")
    .llm(ctx.model())
    .build();

Ensemble.builder()
    .chatLanguageModel(model)
    .agentSynthesizer(customSynthesizer)
    .task(Task.of("Process the data"))
    .build()
    .run();
```

---

## When to use explicit agents

Use an explicit agent when you need:

- A specific persona with a crafted background
- Verbose logging for debugging a particular task
- A custom `responseFormat` instruction
- An agent with `allowDelegation = true`
- A different LLM model than the ensemble default

For all other cases, the zero-ceremony API (`Task.of()`, `Ensemble.run()`) is simpler and
equally effective.
