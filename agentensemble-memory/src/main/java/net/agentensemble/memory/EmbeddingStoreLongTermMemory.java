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
import java.util.List;
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

    /** Metadata key for the agent role. */
    private static final String META_AGENT_ROLE = "agentRole";

    /** Metadata key for the task description. */
    private static final String META_TASK_DESCRIPTION = "taskDescription";

    /** Metadata key for the ISO-8601 timestamp string. */
    private static final String META_TIMESTAMP = "timestamp";

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
        Instant timestamp = entry.getTimestamp() != null ? entry.getTimestamp() : Instant.now();

        Metadata metadata = Metadata.from(META_AGENT_ROLE, entry.getAgentRole())
                .put(META_TASK_DESCRIPTION, entry.getTaskDescription())
                .put(META_TIMESTAMP, timestamp.toString());

        TextSegment segment = TextSegment.from(content, metadata);
        Embedding embedding = embeddingModel.embed(content).content();
        embeddingStore.add(embedding, segment);

        log.debug("Stored long-term memory | Agent: '{}' | Content: {} chars", entry.getAgentRole(), content.length());
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
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }
            Metadata meta = segment.metadata();
            String timestampStr = meta.getString(META_TIMESTAMP);
            Instant timestamp = timestampStr != null ? Instant.parse(timestampStr) : Instant.EPOCH;

            entries.add(MemoryEntry.builder()
                    .content(segment.text())
                    .agentRole(meta.getString(META_AGENT_ROLE))
                    .taskDescription(meta.getString(META_TASK_DESCRIPTION))
                    .timestamp(timestamp)
                    .build());
        }

        log.debug(
                "Retrieved {} long-term memories for query: {}",
                entries.size(),
                query.length() > 80 ? query.substring(0, 80) + "..." : query);

        return entries;
    }
}
