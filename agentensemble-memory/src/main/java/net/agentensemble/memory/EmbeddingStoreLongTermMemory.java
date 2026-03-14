package net.agentensemble.memory;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Long-term memory backed by a LangChain4j {@link EmbeddingStore}.
 *
 * Task outputs are embedded and stored as {@link TextSegment} objects with
 * metadata. Retrieval performs a semantic similarity search using the task
 * description as the query, returning the most relevant past memories.
 *
 * This implementation persists as long as the provided {@code EmbeddingStore}
 * persists -- in-memory stores (for testing) are cleared when the JVM exits,
 * while durable backends (e.g., Chroma, Qdrant, Pinecone) survive across runs.
 *
 * Thread safety: depends on the provided {@code EmbeddingStore} and
 * {@code EmbeddingModel} implementations.
 *
 * Example:
 * <pre>
 * EmbeddingStore&lt;TextSegment&gt; store = new InMemoryEmbeddingStore&lt;&gt;();
 * EmbeddingModel model = new OpenAiEmbeddingModel(...);
 * LongTermMemory ltm = new EmbeddingStoreLongTermMemory(store, model);
 * </pre>
 */
public class EmbeddingStoreLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreLongTermMemory.class);

    /** Metadata key for the ISO-8601 stored-at timestamp string. */
    private static final String META_STORED_AT = "storedAt";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /**
     * @param embeddingStore the store used to persist and search embeddings
     * @param embeddingModel the model used to generate text embeddings
     */
    public EmbeddingStoreLongTermMemory(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        if (embeddingStore == null) {
            throw new IllegalArgumentException("embeddingStore must not be null");
        }
        if (embeddingModel == null) {
            throw new IllegalArgumentException("embeddingModel must not be null");
        }
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void store(MemoryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("MemoryEntry must not be null");
        }
        String content = entry.getContent();
        if (content == null) {
            throw new IllegalArgumentException("MemoryEntry content must not be null");
        }
        Instant storedAt = entry.getStoredAt() != null ? entry.getStoredAt() : Instant.now();

        Metadata metadata = Metadata.from(META_STORED_AT, storedAt.toString());

        // Persist all user-supplied metadata into segment metadata
        if (entry.getMetadata() != null) {
            for (Map.Entry<String, String> e : entry.getMetadata().entrySet()) {
                if (e.getValue() != null) {
                    metadata.put(e.getKey(), e.getValue());
                }
            }
        }

        TextSegment segment = TextSegment.from(content, metadata);
        Embedding embedding = embeddingModel.embed(content).content();
        embeddingStore.add(embedding, segment);

        String agentRole = entry.getMeta(MemoryEntry.META_AGENT_ROLE);
        if (log.isDebugEnabled()) {
            log.debug("Stored long-term memory | Agent: '{}' | Content: {} chars", agentRole, content.length());
        }
    }

    @Override
    public List<MemoryEntry> retrieve(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.0)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        List<MemoryEntry> entries = new ArrayList<>();
        HashMap<String, String> metadataMap = new HashMap<>();
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }
            Metadata meta = segment.metadata();
            String storedAtStr = meta.getString(META_STORED_AT);
            Instant storedAt = storedAtStr != null ? Instant.parse(storedAtStr) : Instant.EPOCH;

            // Reconstruct user metadata from segment metadata
            metadataMap.clear();
            String agentRole = meta.getString(MemoryEntry.META_AGENT_ROLE);
            if (agentRole != null) metadataMap.put(MemoryEntry.META_AGENT_ROLE, agentRole);
            String taskDesc = meta.getString(MemoryEntry.META_TASK_DESCRIPTION);
            if (taskDesc != null) metadataMap.put(MemoryEntry.META_TASK_DESCRIPTION, taskDesc);

            entries.add(MemoryEntry.builder()
                    .content(segment.text())
                    .storedAt(storedAt)
                    .metadata(Map.copyOf(metadataMap))
                    .build());
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "Retrieved {} long-term memories for query: {}",
                    entries.size(),
                    query.length() > 80 ? query.substring(0, 80) + "..." : query);
        }

        return entries;
    }
}
