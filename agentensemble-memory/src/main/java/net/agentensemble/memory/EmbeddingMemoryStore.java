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
 * A {@link MemoryStore} backed by a LangChain4j {@link EmbeddingStore}.
 *
 * <p>Entries are embedded on storage and retrieved via semantic similarity search.
 * All scopes share the same embedding store; scope names are stored as metadata
 * on each {@link TextSegment} for isolation during retrieval.
 *
 * <p>Eviction is not supported on embedding stores and silently performs no operation.
 *
 * <p>Thread safety depends on the thread safety of the provided {@code EmbeddingModel}
 * and {@code EmbeddingStore}.
 */
class EmbeddingMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMemoryStore.class);

    static final String META_SCOPE = "scope";
    static final String META_STORED_AT = "storedAt";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    EmbeddingMemoryStore(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("embeddingModel must not be null");
        }
        if (embeddingStore == null) {
            throw new IllegalArgumentException("embeddingStore must not be null");
        }
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public void store(String scope, MemoryEntry entry) {
        validateScope(scope);
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        String content = entry.getContent();
        if (content == null) {
            throw new IllegalArgumentException("entry content must not be null");
        }
        Instant storedAt = entry.getStoredAt() != null ? entry.getStoredAt() : Instant.now();

        Metadata metadata = Metadata.from(META_SCOPE, scope).put(META_STORED_AT, storedAt.toString());

        // Add all user-supplied metadata entries
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

        if (log.isDebugEnabled()) {
            log.debug("Stored embedding memory entry | scope: '{}' | content: {} chars", scope, content.length());
        }
    }

    @Override
    public List<MemoryEntry> retrieve(String scope, String query, int maxResults) {
        validateScope(scope);
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0, got: " + maxResults);
        }
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
            dev.langchain4j.data.document.Metadata meta = segment.metadata();

            // Filter by scope
            String entryScope = meta.getString(META_SCOPE);
            if (!scope.equals(entryScope)) {
                continue;
            }

            String storedAtStr = meta.getString(META_STORED_AT);
            Instant storedAt = storedAtStr != null ? Instant.parse(storedAtStr) : Instant.EPOCH;

            // Reconstruct all user metadata from segment metadata, excluding internal keys
            metadataMap.clear();
            Map<String, Object> rawMetaMap = meta.toMap();
            for (Map.Entry<String, Object> mEntry : rawMetaMap.entrySet()) {
                String key = mEntry.getKey();
                if (META_SCOPE.equals(key) || META_STORED_AT.equals(key)) {
                    continue;
                }
                Object value = mEntry.getValue();
                if (value != null) {
                    metadataMap.put(key, value.toString());
                }
            }

            entries.add(MemoryEntry.builder()
                    .content(segment.text())
                    .storedAt(storedAt)
                    .metadata(Map.copyOf(metadataMap))
                    .build());
        }

        if (log.isDebugEnabled()) {
            log.debug(
                    "Retrieved {} embedding memory entries | scope: '{}' | query: {}",
                    entries.size(),
                    scope,
                    query.length() > 80 ? query.substring(0, 80) + "..." : query);
        }

        return entries;
    }

    /**
     * Eviction is not supported on embedding stores.
     *
     * <p>Most embedding stores do not provide a mechanism to delete individual entries
     * by content. This method is a no-op. Use a {@link MemoryStore#inMemory()} store if
     * eviction is required.
     */
    @Override
    public void evict(String scope, EvictionPolicy policy) {
        // Eviction is not supported on embedding stores -- no-op
        log.debug("evict() called on EmbeddingMemoryStore for scope '{}': no-op", scope);
    }

    /**
     * Per-scope clear is not portably supported by LangChain4j embedding stores.
     *
     * <p>Most underlying vector stores (Pinecone, Chroma, Qdrant, Milvus, Weaviate, ...)
     * support deletion only by ID or full collection drop -- not by metadata filter -- and
     * the {@code EmbeddingStore} SPI does not expose either operation. As a result, this
     * implementation throws {@link UnsupportedOperationException} so that callers (notably
     * {@code Loop} with {@code FRESH_PER_ITERATION}) do not silently accumulate stale state.
     *
     * <p>If you need {@code FRESH_PER_ITERATION} memory semantics with a vector backend,
     * either (a) use {@link MemoryStore#inMemory()} for the loop's scopes, or (b) configure
     * the loop's scopes to be unique per iteration so prior-iteration entries do not match
     * subsequent retrieval queries.
     */
    @Override
    public void clear(String scope) {
        validateScope(scope);
        throw new UnsupportedOperationException(
                "EmbeddingMemoryStore does not support per-scope clear -- the LangChain4j "
                        + "EmbeddingStore SPI does not expose metadata-filtered deletion. "
                        + "For LoopMemoryMode.FRESH_PER_ITERATION, use MemoryStore.inMemory() for "
                        + "the affected scopes.");
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope must not be null or blank");
        }
    }
}
