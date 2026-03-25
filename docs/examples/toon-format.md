# Example: TOON Context Format

A three-task sequential pipeline using TOON format for token-efficient context
passing between agents. This example demonstrates how switching from JSON to TOON
reduces token consumption by 30-60% with a single configuration change.

---

## What It Does

1. **Researcher** investigates a topic and produces structured findings
2. **Analyst** receives the research (formatted as TOON) and identifies trends
3. **Writer** receives both prior outputs (formatted as TOON) and writes a report

All context flowing between tasks is serialized in TOON format instead of JSON,
reducing the token footprint of each prompt.

---

## Prerequisites

Add the JToon dependency to your project:

=== "Gradle (Kotlin DSL)"

    ```kotlin
    dependencies {
        implementation("net.agentensemble:agentensemble-core:2.x.x")
        implementation("dev.toonformat:jtoon:1.0.9")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>dev.toonformat</groupId>
        <artifactId>jtoon</artifactId>
        <version>1.0.9</version>
    </dependency>
    ```

---

## Full Code

```java
import dev.langchain4j.model.openai.OpenAiChatModel;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.format.ContextFormat;
import net.agentensemble.task.TaskOutput;

import java.nio.file.Path;

public class ToonFormatExample {

    public static void main(String[] args) {
        String topic = args.length > 0
            ? String.join(" ", args)
            : "AI agents in enterprise software";

        var model = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // 1. Define tasks with context dependencies
        Task research = Task.builder()
            .description("Research the latest developments in " + topic)
            .expectedOutput("A structured research summary with key findings, "
                + "statistics, and notable projects")
            .build();

        Task analysis = Task.builder()
            .description("Analyze the research findings and identify the top 3 "
                + "emerging trends in " + topic)
            .expectedOutput("A ranked list of trends with supporting evidence "
                + "and market impact assessment")
            .context(research)  // receives researcher's output as TOON
            .build();

        Task report = Task.builder()
            .description("Write a concise executive summary about " + topic)
            .expectedOutput("A polished 500-word executive summary suitable "
                + "for C-level stakeholders")
            .context(research, analysis)  // receives both outputs as TOON
            .build();

        // 2. Run with TOON context format
        EnsembleOutput result = Ensemble.builder()
            .chatLanguageModel(model)
            .contextFormat(ContextFormat.TOON)  // <-- one line to enable
            .task(research)
            .task(analysis)
            .task(report)
            .verbose(true)
            .build()
            .run();

        // 3. Print results
        System.out.println("=== Executive Summary ===");
        System.out.println(result.getRaw());
        System.out.println();

        // 4. Print token usage per task
        System.out.println("=== Token Usage ===");
        for (TaskOutput output : result.getTaskOutputs()) {
            System.out.printf("  %s: %d tokens (in: %d, out: %d)%n",
                output.getAgentRole(),
                output.getTokenUsage().totalTokenCount(),
                output.getTokenUsage().inputTokenCount(),
                output.getTokenUsage().outputTokenCount());
        }

        // 5. Export trace in TOON format
        result.getTrace().toToon(Path.of("toon-example-trace.toon"));
        System.out.println("\nTrace exported to toon-example-trace.toon");
    }
}
```

---

## How It Works

### Without TOON (default JSON)

When the analyst task receives the researcher's output as context, the prompt
includes something like:

```
## Context from Previous Tasks

Task: Research the latest developments in AI agents...
Output:
{"findings":[{"area":"autonomous agents","growth":"47%","key_players":["AutoGPT","CrewAI","AgentEnsemble"]},{"area":"tool use","growth":"62%","notable":"function calling standardization"}],"market_size":"$4.2B","year":2025}
```

### With TOON

The same context becomes:

```
## Context from Previous Tasks

Task: Research the latest developments in AI agents...
Output:
findings[2]{area,growth,key_players}:
  autonomous agents,47%,"AutoGPT,CrewAI,AgentEnsemble"
  tool use,62%,
market_size: $4.2B
year: 2025
```

The TOON version uses significantly fewer tokens while preserving all the
information the analyst needs.

---

## Running the Example

### From the Examples Module

```bash
./gradlew agentensemble-examples:runToonFormat
```

### Directly

```bash
export OPENAI_API_KEY=sk-...
./gradlew agentensemble-examples:runToonFormat --args="quantum computing"
```

---

## Key Points

- **One line to enable**: `.contextFormat(ContextFormat.TOON)` on the ensemble builder.
- **No code changes in tasks or tools**: The formatting is transparent to agents and tools.
- **JSON schemas preserved**: Structured output schemas (`outputType`) remain in JSON so the LLM knows the expected response format.
- **Graceful fallback**: If you forget the JToon dependency, `Ensemble.build()` fails immediately with clear instructions on what to add.

See the [TOON Format guide](../guides/toon-format.md) for detailed configuration
options and best practices.