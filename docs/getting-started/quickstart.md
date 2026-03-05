# Quickstart

Build your first multi-agent ensemble in five minutes.

---

## 1. Set Up Your Project

Add the dependencies (see [Installation](installation.md)):

```kotlin
dependencies {
    implementation("net.agentensemble:agentensemble-core:0.5.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.32")
}
```

---

## 2. Zero-ceremony: run in three lines

The fastest way to get started -- agents are synthesized automatically from the task
descriptions, no persona setup required:

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;

var model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .build();

EnsembleOutput output = Ensemble.run(model,
    Task.of("Research the latest developments in AI agents"),
    Task.of("Write a concise blog post summarising the research"));

System.out.println(output.getRaw());
```

That's it. AgentEnsemble derives a role, goal, and backstory for each agent automatically.

---

## 3. Per-task LLM and tools

Use different models or tools per task without declaring explicit agents:

```java
EnsembleOutput output = Ensemble.builder()
    .task(Task.builder()
        .description("Research the latest AI developments")
        .expectedOutput("A list of key findings")
        .chatLanguageModel(gpt4oMiniModel)          // cheap model for research
        .tools(List.of(new WebSearchTool()))         // give this task web access
        .build())
    .task(Task.builder()
        .description("Write a blog post based on the research")
        .expectedOutput("A 600-word blog post")
        .chatLanguageModel(gpt4oModel)              // powerful model for writing
        .build())
    .build()
    .run();
```

---

## 4. Explicit agents (power-user)

When you need full control over persona, tools, or verbose logging, declare an explicit
`Agent` and bind it to the task. No registration on the ensemble needed:

```java
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

var researchTask = Task.builder()
    .description("Research the latest developments in {topic}")
    .expectedOutput("A 400-word summary covering current state, key players, and future outlook")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about {topic} based on the provided research")
    .expectedOutput("A 600-800 word blog post in markdown format, ready to publish")
    .agent(writer)
    .context(List.of(researchTask))  // the writer receives the researcher's output
    .build();

EnsembleOutput output = Ensemble.builder()
    .task(researchTask)
    .task(writeTask)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run(Map.of("topic", "AI agents in 2026"));

System.out.println(output.getRaw());
```

---

## 5. Explore the Results

```java
// Final output from the last task
System.out.println(output.getRaw());

// All task outputs in order
for (TaskOutput taskOutput : output.getTaskOutputs()) {
    System.out.printf("[%s] %s%n",
        taskOutput.getAgentRole(),
        taskOutput.getRaw().substring(0, Math.min(200, taskOutput.getRaw().length())));
}

// Execution summary
System.out.printf("Total duration: %s%n", output.getTotalDuration());
System.out.printf("Total tool calls: %d%n", output.getTotalToolCalls());
```

---

## Complete Zero-Ceremony Example

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;

public class QuickstartExample {

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // No agent declarations needed -- synthesized from task descriptions
        EnsembleOutput output = Ensemble.run(model,
            Task.of("Research the latest developments in AI agents in 2026"),
            Task.of("Write a 600-word blog post based on the research"));

        System.out.println(output.getRaw());
    }
}
```

---

## Run the Included Example

The repository includes a complete working example:

```bash
git clone https://github.com/AgentEnsemble/agentensemble.git
cd agentensemble
export OPENAI_API_KEY=your-api-key
./gradlew :agentensemble-examples:run

# Custom topic:
./gradlew :agentensemble-examples:run --args="quantum computing"
```

---

## Next Steps

- [Core Concepts](concepts.md) -- Understand the abstractions
- [Agents Guide](../guides/agents.md) -- Agent synthesis and explicit personas
- [Tasks Guide](../guides/tasks.md) -- Per-task LLM, tools, and maxIterations
- [Tools Guide](../guides/tools.md) -- Give agents tools to use
- [Memory Guide](../guides/memory.md) -- Persist context across tasks and runs
- [Migration Guide](../migration/v1-to-v2.md) -- Upgrading from v1.x
