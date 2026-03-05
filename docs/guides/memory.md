# Memory

AgentEnsemble v2.0.0 introduces task-scoped cross-execution memory, allowing agents to
accumulate and recall information across separate `Ensemble.run()` invocations. Memory is
organized into named **scopes** and backed by a pluggable `MemoryStore`.

---

## Quick Start

```java
// Create a store -- use inMemory() for dev/testing
MemoryStore store = MemoryStore.inMemory();

// Tasks declare which scopes they read from and write to
Task researchTask = Task.builder()
    .description("Research current AI trends")
    .expectedOutput("A research report")
    .agent(researcher)
    .memory("ai-research")  // declares the "ai-research" scope
    .build();

// Wire the store to the ensemble
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .memoryStore(store)
    .build()
    .run();
```

After the run, `store.retrieve("ai-research", ...)` will return the task's output.
On a second run with the same store, the agent's prompt will include entries from the first run.

---

## How It Works

1. **At task startup:** The framework retrieves entries from every declared scope and injects
   them into the agent's prompt as `## Memory: {scope}` sections.

2. **At task completion:** The framework stores the task output into every declared scope.

3. **Cross-run persistence:** Because entries are stored in the `MemoryStore` (not discarded
   between runs), agents in later runs automatically see outputs from earlier runs.

---

## Declaring Scopes on Tasks

Three builder overloads are available:

```java
// Single scope by name
Task.builder()
    .description("Research AI trends")
    .memory("research")
    .build()

// Multiple scopes by name
Task.builder()
    .description("Write summary")
    .memory("research", "draft-history")
    .build()

// Fully configured scope with eviction
Task.builder()
    .description("Research AI trends")
    .memory(MemoryScope.builder()
        .name("research")
        .keepLastEntries(10)   // retain only the 10 most recent entries
        .build())
    .build()
```

---

## MemoryStore Implementations

### In-Memory (development and testing)

```java
MemoryStore store = MemoryStore.inMemory();
```

Entries are accumulated in insertion order per scope. Retrieval returns the most recent
entries (no semantic similarity search). Suitable for development and single-JVM runs.
Entries do **not** survive JVM restarts; reuse the same instance across multiple
`ensemble.run()` calls to simulate cross-run persistence in tests.

### Embedding-Based (production)

```java
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

// Use any LangChain4j EmbeddingStore for durability
EmbeddingStore<TextSegment> embeddingStore = ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8000")
    .collectionName("agentensemble-memory")
    .build();

MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
```

Entries are embedded on storage and retrieved via semantic similarity search. The backing
`EmbeddingStore` controls durability (in-memory, Chroma, Qdrant, Pinecone, pgvector, etc.).

---

## Scope Isolation

A task can only read from scopes it explicitly declares. If task A stores output in
`"research"` and task B declares only `"drafts"`, task B's prompt will **not** contain
task A's output.

```java
// Task A: stores into "research"
Task taskA = Task.builder()
    .description("Research confidential data")
    .memory("research")
    .build();

// Task B: only declares "drafts" -- cannot see "research" entries
Task taskB = Task.builder()
    .description("Write public article")
    .memory("drafts")
    .build();
```

---

## Eviction Policies

`MemoryScope` supports optional eviction to keep scope sizes bounded:

```java
// Retain only the 5 most recent entries
MemoryScope.builder()
    .name("research")
    .keepLastEntries(5)
    .build()

// Retain only entries stored within the past 7 days
MemoryScope.builder()
    .name("research")
    .keepEntriesWithin(Duration.ofDays(7))
    .build()
```

Eviction is applied after each task stores its output. For `MemoryStore.embeddings()`,
eviction is a no-op (embedding stores generally do not support deletion of individual entries).

---

## MemoryTool: Explicit Agent Access

Agents can also interact with memory directly during their ReAct loop using `MemoryTool`:

```java
MemoryStore store = MemoryStore.inMemory();

Agent researcher = Agent.builder()
    .role("Researcher")
    .goal("Research and remember important facts")
    .tools(MemoryTool.of("research", store))
    .llm(llm)
    .build();
```

`MemoryTool` provides two tool methods the LLM can call:
- `storeMemory(key, value)` -- store an arbitrary fact
- `retrieveMemory(query)` -- retrieve relevant memories by query

When the same `MemoryStore` instance is used for both `MemoryTool` and
`Ensemble.builder().memoryStore(...)`, explicit tool access and automatic scope-based
access share the same backing store.

---

## Cross-Run Persistence Example

```java
MemoryStore store = MemoryStore.inMemory(); // reused across runs

Task researchTask = Task.builder()
    .description("Research AI trends for week {week}")
    .expectedOutput("Weekly AI intelligence briefing")
    .agent(analyst)
    .memory("weekly-research")
    .build();

Ensemble ensemble = Ensemble.builder()
    .agent(analyst)
    .task(researchTask)
    .memoryStore(store)
    .build();

// Week 1 -- no prior entries
ensemble.run(Map.of("week", "2026-01-06"));

// Week 2 -- analyst sees Week 1 output in "## Memory: weekly-research" section
ensemble.run(Map.of("week", "2026-01-13"));

// Week 3 -- analyst sees both Week 1 and Week 2 outputs
ensemble.run(Map.of("week", "2026-01-20"));
```

See the [full example](../examples/memory-across-runs.md).

---

## Multiple Tasks Sharing a Scope

Multiple tasks can declare the same scope name. Each task writes its output to the scope
after it completes, so later tasks (in sequential workflow) see earlier tasks' outputs.

```java
MemoryStore store = MemoryStore.inMemory();

Task research = Task.builder()
    .description("Research AI trends")
    .memory("ai-project")
    .build();

Task analysis = Task.builder()
    .description("Analyse the research findings")
    .memory("ai-project")  // also declares "ai-project" -- sees research output
    .build();

Ensemble.builder()
    .task(research)
    .task(analysis)
    .memoryStore(store)
    .build()
    .run();
```

---

## Memory in Prompts

When a task has declared scopes and the scope has prior entries, the agent's user prompt
contains a section for each scope:

```
## Memory: ai-project
The following information from scope "ai-project" may be relevant:

---
Research findings from previous run: AI is accelerating in healthcare and finance...
---

## Task
Analyse the research findings
```

---

## Reference

- [MemoryStore configuration](../reference/memory-configuration.md)
- [Task configuration: memoryScopes](../reference/task-configuration.md)
- [Ensemble configuration: memoryStore](../reference/ensemble-configuration.md)
