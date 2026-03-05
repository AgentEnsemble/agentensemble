package net.agentensemble.memory;

import java.util.List;

/**
 * Strategy interface for persistent long-term memory.
 *
 * Long-term memory persists across ensemble runs. Implementations store task
 * outputs after each task completes and retrieve relevant memories before each
 * task begins based on semantic similarity to the upcoming task description.
 *
 * The primary implementation is {@link EmbeddingStoreLongTermMemory}, which
 * uses a LangChain4j {@code EmbeddingStore} and {@code EmbeddingModel} to
 * perform vector similarity search. Custom implementations can be provided
 * for different storage backends or retrieval strategies.
 *
 * Example:
 * <pre>
 * LongTermMemory longTerm = new EmbeddingStoreLongTermMemory(embeddingStore, embeddingModel);
 * EnsembleMemory memory = EnsembleMemory.builder()
 *     .longTerm(longTerm)
 *     .build();
 * </pre>
 */
public interface LongTermMemory {

    /**
     * Persist a memory entry for future retrieval.
     *
     * Called after each agent task completes. Implementations must durably store
     * the entry so it is available in future ensemble runs.
     *
     * @param entry the memory entry to store; must not be null
     */
    void store(MemoryEntry entry);

    /**
     * Retrieve memory entries relevant to the given query.
     *
     * Called before each agent task begins. The query is typically the task
     * description. Implementations should return the most relevant entries up
     * to the specified limit, ordered by relevance descending.
     *
     * @param query      the text to use as a similarity query (e.g., task description)
     * @param maxResults maximum number of entries to return
     * @return relevant memory entries in descending relevance order; never null
     */
    List<MemoryEntry> retrieve(String query, int maxResults);
}
