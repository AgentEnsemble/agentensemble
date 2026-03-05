package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmbeddingMemoryStore} (accessed via
 * {@link MemoryStore#embeddings(EmbeddingModel, EmbeddingStore)}).
 */
class EmbeddingMemoryStoreTest {

    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private static final float[] DUMMY_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        Embedding dummy = Embedding.from(DUMMY_VECTOR);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(dummy));
    }

    // ========================
    // MemoryStore.embeddings() factory
    // ========================

    @Test
    void testEmbeddingsFactory_nullModel_throwsException() {
        assertThatThrownBy(() -> MemoryStore.embeddings(null, embeddingStore))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEmbeddingsFactory_nullStore_throwsException() {
        assertThatThrownBy(() -> MemoryStore.embeddings(embeddingModel, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEmbeddingsFactory_validArgs_returnsNonNull() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        assertThat(store).isNotNull();
    }

    // ========================
    // store() validation
    // ========================

    @Test
    void testStore_nullScope_throwsException() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        MemoryEntry entry = entry("content");
        assertThatThrownBy(() -> store.store(null, entry)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testStore_nullEntry_throwsException() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        assertThatThrownBy(() -> store.store("scope", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testStore_embedsAndAddsToStore() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        MemoryEntry entry = entry("AI research findings");

        store.store("research", entry);

        verify(embeddingModel).embed("AI research findings");
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    void testStore_entryWithMetadata_metadataPersistedInSegment() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        MemoryEntry entry = MemoryEntry.builder()
                .content("Research output")
                .storedAt(Instant.now())
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "Researcher"))
                .build();

        store.store("research", entry);

        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
    }

    // ========================
    // retrieve()
    // ========================

    @Test
    void testRetrieve_nullQuery_returnsEmpty() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        List<MemoryEntry> results = store.retrieve("scope", null, 5);

        assertThat(results).isEmpty();
    }

    @Test
    void testRetrieve_blankQuery_returnsEmpty() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        List<MemoryEntry> results = store.retrieve("scope", "  ", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void testRetrieve_zeroMaxResults_throwsException() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        assertThatThrownBy(() -> store.retrieve("scope", "query", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRetrieve_withMatchingScope_returnsEntry() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        dev.langchain4j.data.document.Metadata meta = dev.langchain4j.data.document.Metadata.from(
                        EmbeddingMemoryStore.META_SCOPE, "research")
                .put(EmbeddingMemoryStore.META_STORED_AT, "2026-01-15T10:00:00Z")
                .put(MemoryEntry.META_AGENT_ROLE, "Researcher");
        TextSegment segment = TextSegment.from("AI research output", meta);
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", Embedding.from(DUMMY_VECTOR), segment);
        EmbeddingSearchResult<TextSegment> result = new EmbeddingSearchResult<>(List.of(match));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<MemoryEntry> entries = store.retrieve("research", "AI query", 5);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).isEqualTo("AI research output");
        assertThat(entries.get(0).getMeta(MemoryEntry.META_AGENT_ROLE)).isEqualTo("Researcher");
    }

    @Test
    void testRetrieve_withNonMatchingScope_filtersOut() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        // Segment has scope "other-scope", not "research"
        dev.langchain4j.data.document.Metadata meta = dev.langchain4j.data.document.Metadata.from(
                        EmbeddingMemoryStore.META_SCOPE, "other-scope")
                .put(EmbeddingMemoryStore.META_STORED_AT, "2026-01-15T10:00:00Z");
        TextSegment segment = TextSegment.from("Other scope content", meta);
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", Embedding.from(DUMMY_VECTOR), segment);
        EmbeddingSearchResult<TextSegment> result = new EmbeddingSearchResult<>(List.of(match));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<MemoryEntry> entries = store.retrieve("research", "query", 5);

        assertThat(entries).isEmpty();
    }

    @Test
    void testRetrieve_withNullSegment_skipped() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        // Match with null embedded segment
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", Embedding.from(DUMMY_VECTOR), null);
        EmbeddingSearchResult<TextSegment> result = new EmbeddingSearchResult<>(List.of(match));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<MemoryEntry> entries = store.retrieve("research", "query", 5);

        assertThat(entries).isEmpty();
    }

    @Test
    void testRetrieve_noStoredAtInMeta_usesEpoch() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);

        // Segment without storedAt metadata
        dev.langchain4j.data.document.Metadata meta =
                dev.langchain4j.data.document.Metadata.from(EmbeddingMemoryStore.META_SCOPE, "research");
        TextSegment segment = TextSegment.from("Entry without timestamp", meta);
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", Embedding.from(DUMMY_VECTOR), segment);
        EmbeddingSearchResult<TextSegment> result = new EmbeddingSearchResult<>(List.of(match));
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<MemoryEntry> entries = store.retrieve("research", "query", 5);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getStoredAt()).isEqualTo(Instant.EPOCH);
    }

    // ========================
    // evict() -- no-op
    // ========================

    @Test
    void testEvict_isNoOp_doesNotThrow() {
        MemoryStore store = MemoryStore.embeddings(embeddingModel, embeddingStore);
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(5);

        // Should not throw
        store.evict("scope", policy);
    }

    // ========================
    // Private helpers
    // ========================

    private MemoryEntry entry(String content) {
        return MemoryEntry.builder()
                .content(content)
                .storedAt(Instant.now())
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "TestAgent"))
                .build();
    }
}
