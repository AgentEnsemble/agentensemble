# Example: Memory Across Runs

Demonstrates how long-term memory persists task outputs across ensemble runs, allowing agents to build on knowledge from previous executions.

---

## What It Does

A competitive intelligence system that runs weekly. Each run produces a market analysis. Long-term memory means that each week's agents can draw on all prior weeks' analyses when formulating their reports.

---

## Setup

This example uses:
- **Short-term memory** -- agents within a run share outputs automatically
- **Long-term memory** -- past run outputs are retrieved by semantic similarity
- **Entity memory** -- stable company facts are available to all agents in every run

---

## Full Code

```java
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import net.agentensemble.*;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.*;
import net.agentensemble.workflow.Workflow;

import java.util.Map;

public class MemoryAcrossRunsExample {

    public static void main(String[] args) throws InterruptedException {
        var chatModel = OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o-mini")
            .build();

        var embeddingModel = OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("text-embedding-3-small")
            .build();

        // Use InMemoryEmbeddingStore for demonstration.
        // In production, use a durable store (Chroma, Qdrant, etc.)
        // that persists between JVM restarts.
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        LongTermMemory longTerm = new EmbeddingStoreLongTermMemory(store, embeddingModel);

        // Entity memory: stable facts about the company we are analysing
        EntityMemory entities = new InMemoryEntityMemory();
        entities.put("TechCorp", "A publicly traded software company (ticker: TECH) " +
                                  "specialising in cloud infrastructure tools. " +
                                  "Founded 2012. Headquarters: Austin, TX.");
        entities.put("TechCorp CEO", "Sarah Mitchell. Joined as CEO in 2019 from AWS.");

        // Memory configuration shared across all runs
        EnsembleMemory memory = EnsembleMemory.builder()
            .shortTerm(true)
            .longTerm(longTerm)
            .entityMemory(entities)
            .longTermMaxResults(3)
            .build();

        // Agent definitions (reused across runs)
        var analyst = Agent.builder()
            .role("Market Intelligence Analyst")
            .goal("Track market developments, competitive movements, and industry trends for TechCorp")
            .background("You produce weekly intelligence briefings. You draw on historical context " +
                        "from prior analyses to identify trends and changes over time.")
            .llm(chatModel)
            .build();

        var strategist = Agent.builder()
            .role("Strategic Advisor")
            .goal("Translate market intelligence into strategic recommendations for TechCorp")
            .background("You provide actionable strategic recommendations grounded in both " +
                        "current data and historical context.")
            .llm(chatModel)
            .build();

        // Create the ensemble once and reuse it
        var analysisTask = Task.builder()
            .description("Analyse TechCorp's competitive environment for week of {week}. " +
                         "Draw on any relevant historical context from long-term memory.")
            .expectedOutput("A 300-word competitive intelligence briefing for the week of {week}")
            .agent(analyst)
            .build();

        var recommendationTask = Task.builder()
            .description("Provide strategic recommendations for TechCorp based on the week of {week} analysis")
            .expectedOutput("Three specific, actionable strategic recommendations with supporting rationale")
            .agent(strategist)
            .build();

        Ensemble ensemble = Ensemble.builder()
            .agent(analyst)
            .agent(strategist)
            .task(analysisTask)
            .task(recommendationTask)
            .workflow(Workflow.SEQUENTIAL)
            .memory(memory)
            .build();

        // Run 1: Week 1
        System.out.println("=== RUN 1: WEEK 1 ===");
        EnsembleOutput run1 = ensemble.run(Map.of("week", "2026-01-06"));
        System.out.println(run1.getRaw());
        System.out.printf("Duration: %s%n%n", run1.getTotalDuration());

        // Run 2: Week 2 -- long-term memory now contains Week 1 outputs
        System.out.println("=== RUN 2: WEEK 2 ===");
        EnsembleOutput run2 = ensemble.run(Map.of("week", "2026-01-13"));
        System.out.println(run2.getRaw());
        System.out.printf("Duration: %s%n%n", run2.getTotalDuration());

        // Run 3: Week 3 -- agents have access to both prior weeks
        System.out.println("=== RUN 3: WEEK 3 ===");
        EnsembleOutput run3 = ensemble.run(Map.of("week", "2026-01-20"));
        System.out.println(run3.getRaw());
        System.out.printf("Duration: %s%n", run3.getTotalDuration());
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

The example runs three simulated weekly cycles in a single JVM process so that the
`InMemoryEmbeddingStore` accumulates context across all three runs. You will see the
agents reference prior weeks' findings as the runs progress.

---

## How Memory Builds Over Time

After Run 1:
- Long-term memory contains the Week 1 analyst briefing and the Week 1 strategic recommendations

In Run 2:
- Before the analyst task runs, the framework queries the store with "Analyse TechCorp's competitive environment for week of 2026-01-13"
- The Week 1 briefing is retrieved (high semantic similarity) and injected into the analyst's prompt
- The analyst can reference prior context in its Week 2 analysis

After Run 3:
- The strategist has access to memories from both prior weeks
- Trend detection, trajectory analysis, and context-aware recommendations become possible

---

## Using a Durable Store in Production

For real persistence across JVM restarts, swap `InMemoryEmbeddingStore` for a durable implementation:

```java
// Chroma (local or cloud)
EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8000")
    .collectionName("techcorp-intelligence")
    .build();

// Qdrant
EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
    .host("localhost")
    .port(6334)
    .collectionName("techcorp-intelligence")
    .build();

// PostgreSQL pgvector
EmbeddingStore<TextSegment> store = PgVectorEmbeddingStore.builder()
    .datasource(dataSource)
    .table("agentensemble_memories")
    .dimension(1536)  // for text-embedding-3-small
    .build();
```

The `LongTermMemory` and `EnsembleMemory` configuration stays the same.

---

## Updating Entity Facts

Entity facts can be updated between runs as new information becomes available:

```java
entities.put("TechCorp CEO", "Alex Johnson. Appointed CEO in January 2026 following Sarah Mitchell's departure.");

// Next run will use the updated fact in all agent prompts
ensemble.run(Map.of("week", "2026-01-27"));
```
