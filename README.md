# AgentEnsemble

An open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks.

Built natively in Java on top of [LangChain4j](https://github.com/langchain4j/langchain4j), AgentEnsemble is LLM-agnostic and supports any provider LangChain4j supports: OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI, and more.

---

## Core Concepts

| Concept | Description |
|---|---|
| **Agent** | An AI entity with a role, goal, background, and optional tools |
| **Task** | A unit of work assigned to an agent, with a description and expected output |
| **Ensemble** | A group of agents working together on a sequence of tasks |
| **Tool** | A capability an agent can invoke (e.g., search, calculate) |
| **Workflow** | How tasks are executed: `SEQUENTIAL` (now), `HIERARCHICAL` (Phase 2) |

---

## Quickstart

### 1. Add the dependency

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("io.agentensemble:agentensemble-core:0.1.0-SNAPSHOT")

    // Add your preferred LangChain4j model provider:
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

### 2. Define agents

```java
var model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .build();

var researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Find accurate, well-structured information on any given topic")
    .background("You are a veteran researcher with expertise in technology.")
    .llm(model)
    .build();

var writer = Agent.builder()
    .role("Content Writer")
    .goal("Write engaging, well-structured blog posts")
    .llm(model)
    .responseFormat("Use markdown with clear headings and sections.")
    .build();
```

### 3. Define tasks

```java
var researchTask = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A 400-word summary covering current state, key players, and future outlook")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about {topic} based on the provided research")
    .expectedOutput("A 600-800 word blog post in markdown format, ready to publish")
    .agent(writer)
    .context(List.of(researchTask))  // The writer receives the researcher's output as context
    .build();
```

### 4. Run the ensemble

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run(Map.of("topic", "AI agents"));

// Access the final task output
System.out.println(output.getRaw());

// Access all task outputs in order
for (TaskOutput taskOutput : output.getTaskOutputs()) {
    System.out.printf("[%s] %s%n", taskOutput.getAgentRole(), taskOutput.getRaw());
}

// Execution metadata
System.out.printf("Completed in %s, %d tool calls%n",
    output.getTotalDuration(), output.getTotalToolCalls());
```

---

## Agent Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `role` | `String` | required | The agent's role/title |
| `goal` | `String` | required | The agent's primary objective |
| `background` | `String` | `null` | Persona context for the system prompt |
| `tools` | `List<Object>` | `[]` | Tools the agent can use |
| `llm` | `ChatModel` | required | Any LangChain4j `ChatModel` |
| `verbose` | `boolean` | `false` | Log prompts and responses at INFO |
| `maxIterations` | `int` | `25` | Max tool-call iterations before forcing final answer |
| `responseFormat` | `String` | `""` | Extra formatting instructions in the system prompt |

---

## Task Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `description` | `String` | required | What the agent should do. Supports `{variable}` templates. |
| `expectedOutput` | `String` | required | What the output should look like |
| `agent` | `Agent` | required | The agent assigned to this task |
| `context` | `List<Task>` | `[]` | Prior tasks whose outputs feed into this task |

---

## Ensemble Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `agents` | `List<Agent>` | required | All agents participating |
| `tasks` | `List<Task>` | required | All tasks to execute, in order |
| `workflow` | `Workflow` | `SEQUENTIAL` | Execution strategy |
| `verbose` | `boolean` | `false` | Elevates execution logging to INFO |

---

## Creating Tools

### Option 1: Implement `AgentTool`

```java
public class WebSearchTool implements AgentTool {
    public String name() { return "web_search"; }
    public String description() { return "Search the web. Input: a search query string."; }
    public ToolResult execute(String input) {
        String results = performSearch(input);
        return ToolResult.success(results);
    }
}

var agent = Agent.builder()
    .role("Researcher")
    .goal("Find information")
    .tools(List.of(new WebSearchTool()))
    .llm(model)
    .build();
```

### Option 2: Use LangChain4j `@Tool` annotation

```java
public class MathTools {
    @Tool("Calculate a mathematical expression. Input: an expression like '2 + 3'.")
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

Both approaches can be combined in a single agent's tool list.

---

## Template Variables

Use `{variable}` placeholders in task descriptions and expected outputs:

```java
var task = Task.builder()
    .description("Research {topic} in {year}")
    .expectedOutput("A report on {topic}")
    .agent(researcher)
    .build();

// Resolve at run time:
ensemble.run(Map.of("topic", "quantum computing", "year", "2026"));
```

Use `{{variable}}` to include a literal `{variable}` in the text without substitution.

---

## Error Handling

```java
try {
    EnsembleOutput output = ensemble.run();
} catch (ValidationException e) {
    // Invalid configuration (bad agent/task setup)
    System.err.println("Configuration error: " + e.getMessage());
} catch (TaskExecutionException e) {
    // A task failed during execution
    System.err.println("Task failed: " + e.getTaskDescription());
    System.err.println("Agent: " + e.getAgentRole());

    // Partial results from tasks that completed before the failure
    for (TaskOutput completed : e.getCompletedTaskOutputs()) {
        System.out.println("Completed: " + completed.getTaskDescription());
    }
} catch (PromptTemplateException e) {
    // Missing template variables
    System.err.println("Missing variables: " + e.getMissingVariables());
}
```

---

## Logging

AgentEnsemble uses SLF4J. Add your preferred implementation (Logback, Log4j2, etc.) to your project.

**Logback example with MDC support:**

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss} %-5level [%X{ensemble.id:-}] [%X{task.index:-}] [%X{agent.role:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="io.agentensemble" level="INFO"/>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

**MDC keys available during execution:**

| Key | Example Value |
|---|---|
| `ensemble.id` | UUID per `run()` call |
| `task.index` | `"2/5"` |
| `agent.role` | `"Senior Research Analyst"` |

---

## Running the Example

```bash
git clone https://github.com/AgentEnsemble/agentensemble.git
cd agentensemble
export OPENAI_API_KEY=your-api-key
./gradlew :agentensemble-examples:run

# Custom topic:
./gradlew :agentensemble-examples:run --args="quantum computing"
```

---

## Building from Source

```bash
./gradlew build       # Compile and run all tests
./gradlew test        # Run tests only
```

---

## Design Documentation

See [`docs/design/`](docs/design/) for full specifications:

- [01 - Overview](docs/design/01-overview.md)
- [02 - Architecture](docs/design/02-architecture.md)
- [03 - Domain Model](docs/design/03-domain-model.md)
- [04 - Execution Engine](docs/design/04-execution-engine.md)
- [05 - Prompt Templates](docs/design/05-prompt-templates.md)
- [06 - Tool System](docs/design/06-tool-system.md)
- [07 - Template Resolver](docs/design/07-template-resolver.md)
- [08 - Error Handling](docs/design/08-error-handling.md)
- [09 - Logging](docs/design/09-logging.md)
- [10 - Concurrency](docs/design/10-concurrency.md)
- [11 - Configuration Reference](docs/design/11-configuration.md)
- [12 - Testing Strategy](docs/design/12-testing-strategy.md)
- [13 - Future Roadmap](docs/design/13-future-roadmap.md)

---

## What's Next (Roadmap)

| Phase | Features |
|---|---|
| v0.2.0 | Hierarchical workflow (manager agent delegates) |
| v0.3.0 | Memory system (short-term, long-term, entity) |
| v0.4.0 | Agent delegation |
| v0.5.0 | Parallel workflow (virtual threads) |
| v0.6.0 | Structured output (typed output parsing) |
| v1.0.0 | Callbacks, streaming, guardrails, built-in tools |

---

## Contributing

Contributions are welcome. Please open an issue to discuss proposed changes before submitting a pull request.

---

## License

MIT License. See [LICENSE](LICENSE).
