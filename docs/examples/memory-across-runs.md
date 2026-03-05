# Example: Memory Across Runs

Demonstrates how task-scoped memory persists outputs across separate `Ensemble.run()`
invocations, allowing agents to accumulate context and improve over time.

---

## What It Does

A competitive intelligence system that runs weekly. Each run produces a market analysis.
Scoped memory means that each week's agents can draw on all prior weeks' analyses when
formulating their reports.

---

## Setup

This example uses `MemoryStore.inMemory()` and a shared scope named `"weekly-research"`.
Both the analyst and strategist tasks declare the same scope, so:

- The strategist task sees the analyst's output from the current run (the analyst runs first)
- On subsequent runs, both agents see all outputs from prior runs

---

## Full Code

```java
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.workflow.Workflow;

import java.util.Map;

public class MemoryAcrossRunsExample {

    public static void main(String[] args) {
        var chatModel = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        // Shared store -- reused across all runs to persist entries
        MemoryStore store = MemoryStore.inMemory();

        // For production, use a durable embedding store:
        //   EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()...build();
        //   EmbeddingStore<TextSegment> embeddingStore = ChromaEmbeddingStore.builder()...build();
        //   MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        // Agent definitions (reused across runs)
        var analyst = Agent.builder()
            .role("Market Intelligence Analyst")
            .goal("Track market developments for TechCorp, drawing on prior analyses to identify trends")
            .llm(chatModel)
            .build();

        var strategist = Agent.builder()
            .role("Strategic Advisor")
            .goal("Provide actionable strategic recommendations grounded in both current and historical context")
            .llm(chatModel)
            .build();

        // Tasks declare the shared scope
        var analysisTask = Task.builder()
            .description("Analyse TechCorp's competitive environment for week of {week}. " +
                         "Draw on any relevant historical context from memory.")
            .expectedOutput("A 300-word competitive intelligence briefing for the week of {week}")
            .agent(analyst)
            .memory("weekly-research")
            .build();

        var recommendationTask = Task.builder()
            .description("Provide strategic recommendations for TechCorp based on the week of {week} analysis")
            .expectedOutput("Three specific, actionable strategic recommendations")
            .agent(strategist)
            .memory("weekly-research") // also reads prior research
            .build();

        // Build the ensemble once -- reuse across runs
        Ensemble ensemble = Ensemble.builder()
            .agent(analyst)
            .agent(strategist)
            .task(analysisTask)
            .task(recommendationTask)
            .workflow(Workflow.SEQUENTIAL)
            .memoryStore(store)
            .build();

        // Run 1: Week 1 -- no prior memory
        System.out.println("=== RUN 1: WEEK 1 ===");
        EnsembleOutput run1 = ensemble.run(Map.of("week", "2026-01-06"));
        System.out.println(run1.getRaw());

        // Run 2: Week 2 -- memory contains Week 1 outputs
        System.out.println("\n=== RUN 2: WEEK 2 ===");
        EnsembleOutput run2 = ensemble.run(Map.of("week", "2026-01-13"));
        System.out.println(run2.getRaw());

        // Run 3: Week 3 -- agents have access to both prior weeks
        System.out.println("\n=== RUN 3: WEEK 3 ===");
        EnsembleOutput run3 = ensemble.run(Map.of("week", "2026-01-20"));
        System.out.println(run3.getRaw());
    }
}
```

---

## Running the Example

```bash
git clone https://github.com/AgentEnsemble/agentensemble.git
cd agentensemble
export OPENAI_API_KEY=your-api-key

./gradlew :agentensemble-examples:runMemoryAcrossRuns
```

---

## How Memory Builds Over Time

**Run 1 (Week 1):**

- Analyst runs with no prior memory. Produces Week 1 briefing, stored in `"weekly-research"`.
- Strategist sees analyst's Week 1 briefing in its prompt (stored in same scope, runs after analyst).

**Run 2 (Week 2):**

- Analyst's prompt contains a `## Memory: weekly-research` section with Week 1 outputs.
- Analyst references prior context in its Week 2 analysis.
- Strategist sees both Week 1 and Week 2 analyst outputs.

**Run 3 (Week 3) and beyond:**

- Trend detection, trajectory analysis, and context-aware recommendations become possible.
- Agents can compare current state against prior weeks.

---

## Using a Durable Store in Production

For real persistence across JVM restarts, use an embedding-backed store:

```java
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

// Chroma
EmbeddingStore<TextSegment> embeddingStore = ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8000")
    .collectionName("techcorp-weekly-research")
    .build();

MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
```

The ensemble code is identical. Only the `store` construction changes.

---

## Keeping Scope Size Bounded

Use `MemoryScope` with an eviction policy to prevent unbounded growth:

```java
var analysisTask = Task.builder()
    .description("Analyse TechCorp's competitive environment for week of {week}")
    .expectedOutput("Competitive intelligence briefing")
    .agent(analyst)
    .memory(MemoryScope.builder()
        .name("weekly-research")
        .keepLastEntries(10)  // retain only the 10 most recent weeks
        .build())
    .build();
```

See the [Memory guide](../guides/memory.md) for full eviction policy documentation.
