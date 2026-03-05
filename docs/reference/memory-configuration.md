# Memory Configuration Reference

Memory types in AgentEnsemble are provided by the `agentensemble-memory` module, which is a separate
optional dependency from `agentensemble-core`.

## Module Coordinates

```kotlin
// Gradle (Kotlin DSL)
implementation("net.agentensemble:agentensemble-memory:2.0.0")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>net.agentensemble</groupId>
    <artifactId>agentensemble-memory</artifactId>
    <version>2.0.0</version>
</dependency>
```

`agentensemble-core` declares a `compileOnly` dependency on `agentensemble-memory`, meaning
`agentensemble-memory` is **not** included transitively. Add it explicitly when you use any memory
feature.

---

## `EnsembleMemory`

Configured via `EnsembleMemory.builder()`. At least one memory type must be enabled.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `shortTerm` | `boolean` | One of these must be set | `false` | Accumulate all task outputs within a run and inject into subsequent agents |
| `longTerm` | `LongTermMemory` | One of these must be set | `null` | Cross-run vector-store persistence |
| `entityMemory` | `EntityMemory` | One of these must be set | `null` | Named entity fact store |
| `longTermMaxResults` | `int` | No | `5` | Maximum number of long-term memories retrieved per task. Must be greater than zero. |

---

## `LongTermMemory` Interface

```java
public interface LongTermMemory {
    void store(MemoryEntry entry);
    List<MemoryEntry> retrieve(String query, int maxResults);
}
```

### `EmbeddingStoreLongTermMemory`

The built-in implementation. Uses a LangChain4j `EmbeddingStore<TextSegment>` and `EmbeddingModel`.

**Constructor:**
```java
new EmbeddingStoreLongTermMemory(
    EmbeddingStore<TextSegment> embeddingStore,
    EmbeddingModel embeddingModel
)
```

Both arguments must be non-null.

**LangChain4j Embedding Stores:** `InMemoryEmbeddingStore` (development), Chroma, Qdrant, Pinecone, Weaviate, Milvus, PostgreSQL pgvector, Redis, and more.

**LangChain4j Embedding Models:** OpenAI `text-embedding-3-small` / `text-embedding-3-large`, Ollama embeddings, Azure OpenAI embeddings, and more.

---

## `EntityMemory` Interface

```java
public interface EntityMemory {
    void put(String entityName, String fact);
    Optional<String> get(String entityName);
    Map<String, String> getAll();
    boolean isEmpty();
}
```

### `InMemoryEntityMemory`

The built-in implementation backed by a `ConcurrentHashMap`.

```java
EntityMemory entities = new InMemoryEntityMemory();
entities.put("Acme Corp", "A B2B SaaS company with 15,000 enterprise customers");
entities.put("Alice Chen", "Head of Product, background in NLP research");
```

Entity names are trimmed when stored and looked up. `put()` replaces any existing fact for the same entity name.

---

## `MemoryEntry`

Each recorded task output produces a `MemoryEntry` with:

| Field | Type | Description |
|---|---|---|
| `content` | `String` | The raw output from the task |
| `agentRole` | `String` | The role of the agent that produced it |
| `taskDescription` | `String` | The task description |
| `timestamp` | `Instant` | When the output was recorded |

---

## Custom Implementations

### Custom Long-Term Memory

```java
public class PostgresLongTermMemory implements LongTermMemory {
    @Override
    public void store(MemoryEntry entry) {
        db.insert("memories", entry.getContent(), entry.getAgentRole(),
                  entry.getTaskDescription(), entry.getTimestamp());
    }

    @Override
    public List<MemoryEntry> retrieve(String query, int maxResults) {
        return db.vectorSearch(query, maxResults).stream()
            .map(row -> MemoryEntry.builder()
                .content(row.content())
                .agentRole(row.agentRole())
                .taskDescription(row.taskDescription())
                .timestamp(row.timestamp())
                .build())
            .toList();
    }
}
```

### Custom Entity Memory

```java
public class ConfiguredEntityMemory implements EntityMemory {
    private final Map<String, String> facts;

    public ConfiguredEntityMemory(Map<String, String> facts) {
        this.facts = new HashMap<>(facts);
    }

    @Override
    public void put(String entityName, String fact) { facts.put(entityName.trim(), fact); }

    @Override
    public Optional<String> get(String entityName) {
        return Optional.ofNullable(facts.get(entityName == null ? null : entityName.trim()));
    }

    @Override
    public Map<String, String> getAll() { return Collections.unmodifiableMap(facts); }

    @Override
    public boolean isEmpty() { return facts.isEmpty(); }
}
```

---

## Example: All Memory Types

```java
EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
    .baseUrl("http://localhost:8000")
    .collectionName("agentensemble-memories")
    .build();

EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("text-embedding-3-small")
    .build();

EntityMemory entities = new InMemoryEntityMemory();
entities.put("Project Horizon", "Q4 initiative to expand into the APAC market");

EnsembleMemory memory = EnsembleMemory.builder()
    .shortTerm(true)
    .longTerm(new EmbeddingStoreLongTermMemory(store, embeddingModel))
    .entityMemory(entities)
    .longTermMaxResults(3)
    .build();
```
