package net.agentensemble.memory;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.List;

/**
 * SPI for task-scoped cross-execution memory.
 *
 * <p>A {@code MemoryStore} holds memory entries organized by named scopes. Tasks declare
 * which scopes they read from and write to via
 * {@code Task.builder().memory(String)} and related methods. The
 * framework automatically retrieves entries before task execution and stores outputs after
 * completion.
 *
 * <p>Use the built-in factories:
 * <ul>
 *   <li>{@link #inMemory()} -- suitable for development, testing, and single-JVM runs</li>
 *   <li>{@link #embeddings(EmbeddingModel, EmbeddingStore)} -- production-grade,
 *       vector-similarity-based retrieval backed by any LangChain4j embedding store</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe when used with {@code Workflow.PARALLEL}.
 *
 * Example:
 * <pre>
 * MemoryStore store = MemoryStore.inMemory();
 *
 * Ensemble.builder()
 *     .agent(researcher)
 *     .task(Task.builder()
 *         .description("Research AI trends")
 *         .memory("research")
 *         .build())
 *     .memoryStore(store)
 *     .build()
 *     .run();
 * </pre>
 */
public interface MemoryStore {

    /**
     * Store a memory entry in the specified scope.
     *
     * <p>Implementations must be durable for the lifetime of the store instance.
     *
     * @param scope the scope name; must not be null or blank
     * @param entry the entry to store; must not be null
     */
    void store(String scope, MemoryEntry entry);

    /**
     * Retrieve memory entries from the specified scope relevant to the given query.
     *
     * <p>The query is typically the upcoming task description. Implementations should
     * return the most relevant entries up to {@code maxResults}, ordered by relevance
     * descending. For implementations without semantic search (e.g., {@link #inMemory()}),
     * the most recent entries are returned.
     *
     * @param scope      the scope name; must not be null or blank
     * @param query      the query text; if null or blank, returns most recent entries
     * @param maxResults maximum number of entries to return; must be positive
     * @return relevant entries, ordered by relevance or recency; never null
     */
    List<MemoryEntry> retrieve(String scope, String query, int maxResults);

    /**
     * Apply the given eviction policy to the specified scope.
     *
     * <p>After eviction, only the entries returned by the policy are retained.
     *
     * @param scope  the scope name; must not be null or blank
     * @param policy the eviction policy to apply; must not be null
     */
    void evict(String scope, EvictionPolicy policy);

    /**
     * Create a lightweight in-memory store backed by a {@code ConcurrentHashMap}.
     *
     * <p>Entries are accumulated in insertion order per scope. Retrieval returns the most
     * recent entries (no semantic similarity). Suitable for development, testing, and
     * single-JVM use cases where cross-JVM persistence is not required.
     *
     * @return a new in-memory store
     */
    static MemoryStore inMemory() {
        return new InMemoryStore();
    }

    /**
     * Create a production-grade vector-based store backed by a LangChain4j embedding
     * model and embedding store.
     *
     * <p>Entries are embedded on store and retrieved via semantic similarity search.
     * The underlying embedding store provides durability semantics (e.g., in-memory,
     * Chroma, Qdrant, Pinecone, etc.).
     *
     * @param embeddingModel the model used to generate embeddings; must not be null
     * @param embeddingStore the store used to persist and search embeddings; must not
     *                       be null
     * @return a new embedding-backed store
     */
    static MemoryStore embeddings(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        return new EmbeddingMemoryStore(embeddingModel, embeddingStore);
    }
}
