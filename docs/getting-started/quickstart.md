# Quickstart

Build your first multi-agent ensemble in five minutes.

---

## 1. Set Up Your Project

Add the dependencies (see [Installation](installation.md)):

```kotlin
dependencies {
    implementation("net.agentensemble:agentensemble-core:0.4.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
}
```

---

## 2. Create a Model

```java
import dev.langchain4j.model.openai.OpenAiChatModel;

var model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .build();
```

---

## 3. Define Your Agents

Each agent has a role, a goal, and an LLM:

```java
import net.agentensemble.Agent;

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

---

## 4. Define Your Tasks

Each task describes work for one agent:

```java
import net.agentensemble.Task;

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
```

---

## 5. Run the Ensemble

```java
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workflow.Workflow;

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run(Map.of("topic", "AI agents in 2026"));

System.out.println(output.getRaw());
```

---

## 6. Explore the Results

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

## Complete Example

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.workflow.Workflow;

import java.util.List;
import java.util.Map;

public class QuickstartExample {

    public static void main(String[] args) {
        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        var researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Find accurate, well-structured information on any given topic")
            .llm(model)
            .build();

        var writer = Agent.builder()
            .role("Content Writer")
            .goal("Write engaging, well-structured blog posts")
            .llm(model)
            .build();

        var researchTask = Task.builder()
            .description("Research the latest developments in {topic}")
            .expectedOutput("A 400-word summary")
            .agent(researcher)
            .build();

        var writeTask = Task.builder()
            .description("Write a blog post about {topic} based on the research")
            .expectedOutput("A 600-800 word blog post in markdown")
            .agent(writer)
            .context(List.of(researchTask))
            .build();

        EnsembleOutput output = Ensemble.builder()
            .agent(researcher)
            .agent(writer)
            .task(researchTask)
            .task(writeTask)
            .workflow(Workflow.SEQUENTIAL)
            .build()
            .run(Map.of("topic", "AI agents in 2026"));

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
- [Agents Guide](../guides/agents.md) -- More agent configuration options
- [Tools Guide](../guides/tools.md) -- Give agents tools to use
- [Memory Guide](../guides/memory.md) -- Persist context across tasks and runs
- [Delegation Guide](../guides/delegation.md) -- Let agents delegate to peers
