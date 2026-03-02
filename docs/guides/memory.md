# Memory

AgentEnsemble supports three complementary memory types that can be used independently or together. Memory is configured via `EnsembleMemory` on the `Ensemble` builder.

---

## Memory Types

### Short-Term Memory

Accumulates all task outputs produced during a single `run()` call and injects them into each subsequent agent's prompt. When short-term memory is active, agents automatically receive the outputs of all prior tasks without requiring explicit `context` declarations.

**Use when:** You want agents to be aware of everything that has been done in the current run, without manually wiring context dependencies.

### Long-Term Memory

Persists task outputs across ensemble runs using a LangChain4j `EmbeddingStore`. Before each task, the framework searches the store for past outputs relevant to the current task description and injects them into the agent's prompt.

**Use when:** You run the same or similar ensembles repeatedly and want agents to benefit from previous runs. For example, a weekly report ensemble that learns from prior weeks.

### Entity Memory

A user-populated key-value store of known facts about named entities. All stored facts are injected into every agent's prompt for every task. Entity memory is seeded before running the ensemble.

**Use when:** You have stable, well-known facts that should be consistently available to all agents. For example, facts about a company, a project, or key people.

---

## Configuring Memory

### Short-Term Only

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
    .run();
```

With short-term memory enabled, the `writeTask` does not need `context(List.of(researchTask))` -- the researcher's output is automatically injected.

### Long-Term Memory

Long-term memory requires a LangChain4j `EmbeddingStore` and `EmbeddingModel`:

```java
// For development/testing -- use a durable store in production
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

LongTermMemory longTerm = new EmbeddingStoreLongTermMemory(store, embeddingModel);

EnsembleMemory memory = EnsembleMemory.builder()
    .longTerm(longTerm)
    .longTermMaxResults(5)   // retrieve up to 5 relevant memories per task (default: 5)
    .build();
```

**Supported stores (via LangChain4j):** Chroma, Qdrant, Pinecone, Weaviate, Milvus, Redis, PostgreSQL (pgvector), and more.

### Entity Memory

```java
EntityMemory entities = new InMemoryEntityMemory();
entities.put("Acme Corp", "A mid-sized SaaS company founded in 2015, publicly traded as ACME. " +
                          "Their primary product is a B2B CRM platform with 15,000 customers.");
entities.put("Alice Chen", "Head of Product at Acme Corp. Background in ML research. " +
                           "Joined in 2021 from Google.");

EnsembleMemory memory = EnsembleMemory.builder()
    .entityMemory(entities)
    .build();
```

### All Three Together

```java
EmbeddingStore<TextSegment> store = loadDurableStore();  // e.g., Chroma
EmbeddingModel embeddingModel = buildEmbeddingModel();

EntityMemory entities = new InMemoryEntityMemory();
entities.put("Project Phoenix", "Internal codename for the new mobile app, launching in Q3.");

EnsembleMemory memory = EnsembleMemory.builder()
    .shortTerm(true)
    .longTerm(new EmbeddingStoreLongTermMemory(store, embeddingModel))
    .entityMemory(entities)
    .longTermMaxResults(3)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .memory(memory)
    .build()
    .run(Map.of("topic", "Project Phoenix launch strategy"));
```

---

## How Memory Appears in Prompts

When memory is active, the agent's user prompt contains additional sections before the task description:

```
## Short-Term Memory (Current Run)
[Prior task outputs from this run]

## Long-Term Memory
[Relevant outputs from previous runs, retrieved by semantic similarity]

## Entity Knowledge
[All entity facts from the entity memory store]

## Your Task
[Task description and expected output]
```

Short-term memory replaces the "Context from prior tasks" section when active.

---

## Memory Lifecycle

| Memory type | Scope | Created | Cleared |
|---|---|---|---|
| Short-term | Per `run()` call | At start of `run()` | At end of `run()` |
| Long-term | Persistent | Controlled by your `EmbeddingStore` | When you clear the store |
| Entity | Controlled by you | Before `run()` | When you update the store |

Long-term memory and entity memory are shared across runs. Their lifecycle is entirely under your control -- AgentEnsemble never clears or modifies them outside of storing new entries.

---

## Custom Long-Term Memory

You can implement the `LongTermMemory` interface for custom storage backends or retrieval strategies:

```java
public class MyDatabaseMemory implements LongTermMemory {

    @Override
    public void store(MemoryEntry entry) {
        // Persist to your database
    }

    @Override
    public List<MemoryEntry> retrieve(String query, int maxResults) {
        // Return relevant entries for the given query
        return myDatabase.search(query, maxResults);
    }
}
```

---

## Memory Configuration Reference

See the [Memory Configuration reference](../reference/memory-configuration.md) for the complete field table.
