<p align="center">
  <img src="assets/brand/agentensemble-logo-horizontal.svg" alt="AgentEnsemble" width="420">
</p>

<p align="center">An open-source Java 21 framework for orchestrating teams of AI agents that collaborate to accomplish complex tasks.</p>

---

Built natively in Java on top of [LangChain4j](https://github.com/langchain4j/langchain4j), AgentEnsemble is LLM-agnostic and supports any provider LangChain4j supports: OpenAI, Anthropic, Ollama, Azure OpenAI, Amazon Bedrock, Google Vertex AI, and more.

---

## Core Concepts

| Concept | Description |
|---|---|
| **Agent** | An AI entity with a role, goal, background, and optional tools |
| **Task** | A unit of work assigned to an agent, with a description and expected output |
| **Ensemble** | A group of agents working together on a sequence of tasks |
| **Tool** | A capability an agent can invoke (e.g., search, calculate) |
| **Workflow** | How tasks are executed: `SEQUENTIAL` or `HIERARCHICAL` (manager delegates to workers) |
| **Memory** | Optional per-run and cross-run context: short-term, long-term (vector store), and entity memory |

---

## Quickstart

### 1. Add the dependency

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation("net.agentensemble:agentensemble-core:0.3.0")

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

## Hierarchical Workflow

With `Workflow.HIERARCHICAL`, a Manager agent is created automatically. It receives the full list of tasks and a description of each available worker agent. The Manager uses a `delegateTask` tool to dispatch tasks to the appropriate workers, then synthesizes their outputs into a final result.

You do not assign tasks to specific agents when using a hierarchical workflow -- the Manager decides which worker handles each task based on their roles and goals.

```java
var analyst = Agent.builder()
    .role("Data Analyst")
    .goal("Analyse datasets and surface key trends")
    .llm(model)
    .build();

var writer = Agent.builder()
    .role("Report Writer")
    .goal("Write clear executive-level reports from analytical findings")
    .llm(model)
    .build();

// In hierarchical workflow, tasks do not require an agent assignment
var analyseTask = Task.builder()
    .description("Analyse Q4 sales data and identify the top three trends")
    .expectedOutput("A structured analysis with three clearly identified trends")
    .agent(analyst)
    .build();

var reportTask = Task.builder()
    .description("Write an executive summary based on the Q4 sales analysis")
    .expectedOutput("A one-page executive summary suitable for board presentation")
    .agent(writer)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(analyst)
    .agent(writer)
    .task(analyseTask)
    .task(reportTask)
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(model)           // LLM used by the Manager agent
    .managerMaxIterations(20)    // Max tool-call iterations for the Manager (default: 20)
    .build()
    .run();

// output.getRaw() contains the Manager's synthesised final result
// output.getTaskOutputs() contains each worker's output followed by the Manager's output
System.out.println(output.getRaw());
```

If `managerLlm` is not set, the Manager uses the first agent's LLM. All worker agents participate in the same memory context when memory is configured (see [Memory System](#memory-system) below).

---

## Memory System

AgentEnsemble supports three complementary memory types, all configured via `EnsembleMemory` on the `Ensemble`. At least one type must be enabled when a memory configuration is provided.

### Short-term memory

Accumulates every task output produced during a single `run()` call and injects it into each subsequent agent's prompt. When short-term memory is active it replaces the need to declare explicit `context` dependencies between tasks.

```java
EnsembleMemory memory = EnsembleMemory.builder()
    .shortTerm(true)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .memory(memory)
    .build()
    .run(Map.of("topic", "AI agents"));
```

### Long-term memory

Persists task outputs across ensemble runs using a LangChain4j `EmbeddingStore`. Before each task begins, relevant past memories are retrieved by semantic similarity to the task description and injected into the agent's prompt.

```java
// Use any LangChain4j EmbeddingStore -- in-memory for development,
// durable (Chroma, Qdrant, Pinecone, etc.) for production
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

LongTermMemory longTerm = new EmbeddingStoreLongTermMemory(store, embeddingModel);

EnsembleMemory memory = EnsembleMemory.builder()
    .longTerm(longTerm)
    .longTermMaxResults(5)  // Max memories retrieved per task (default: 5)
    .build();
```

### Entity memory

A key-value store of known facts about named entities. All facts are injected into every agent's prompt so agents share consistent, pre-seeded knowledge. Users populate entity memory before running the ensemble.

```java
EntityMemory entities = new InMemoryEntityMemory();
entities.put("Acme Corp", "A mid-sized SaaS company founded in 2015, publicly traded as ACME");
entities.put("Alice", "The lead researcher on this project, specialising in NLP");

EnsembleMemory memory = EnsembleMemory.builder()
    .entityMemory(entities)
    .build();
```

### Combining all three memory types

```java
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

EntityMemory entities = new InMemoryEntityMemory();
entities.put("Acme Corp", "A mid-sized SaaS company founded in 2015");

EnsembleMemory memory = EnsembleMemory.builder()
    .shortTerm(true)
    .longTerm(new EmbeddingStoreLongTermMemory(store, embeddingModel))
    .entityMemory(entities)
    .longTermMaxResults(5)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .memory(memory)
    .build()
    .run(Map.of("topic", "Acme Corp product strategy"));
```

### EnsembleMemory configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `shortTerm` | `boolean` | `false` | Accumulate all task outputs within a run and inject into subsequent agents |
| `longTerm` | `LongTermMemory` | `null` | Cross-run vector-store persistence; use `EmbeddingStoreLongTermMemory` |
| `entityMemory` | `EntityMemory` | `null` | Named entity fact store; use `InMemoryEntityMemory` |
| `longTermMaxResults` | `int` | `5` | Maximum memories retrieved per task when long-term memory is enabled |

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
| `context` | `List<Task>` | `[]` | Prior tasks whose outputs feed into this task (sequential workflow) |

---

## Ensemble Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `agents` | `List<Agent>` | required | All agents participating |
| `tasks` | `List<Task>` | required | All tasks to execute |
| `workflow` | `Workflow` | `SEQUENTIAL` | Execution strategy: `SEQUENTIAL` or `HIERARCHICAL` |
| `managerLlm` | `ChatModel` | first agent's LLM | LLM for the Manager agent (hierarchical workflow only) |
| `managerMaxIterations` | `int` | `20` | Max tool-call iterations for the Manager agent (hierarchical workflow only) |
| `memory` | `EnsembleMemory` | `null` | Memory configuration; see [Memory System](#memory-system) |
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
    <logger name="net.agentensemble" level="INFO"/>
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
| ~~v0.2.0~~ | ~~Hierarchical workflow (manager agent delegates)~~ |
| ~~v0.3.0~~ | ~~Memory system (short-term, long-term, entity)~~ |
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
