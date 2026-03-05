package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

class EmbeddingStoreLongTermMemoryTest {

    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> embeddingStore = mock(EmbeddingStore.class);

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private EmbeddingStoreLongTermMemory ltm;

    private static final float[] DUMMY_VECTOR = new float[] {0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        ltm = new EmbeddingStoreLongTermMemory(embeddingStore, embeddingModel);

        // Default: embed() returns a fixed embedding
        Embedding dummyEmbedding = Embedding.from(DUMMY_VECTOR);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(dummyEmbedding));
    }

    private MemoryEntry entry(String content, String role) {
        return MemoryEntry.builder()
                .content(content)
                .storedAt(Instant.parse("2026-01-15T10:00:00Z"))
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, role, MemoryEntry.META_TASK_DESCRIPTION, "Research task"))
                .build();
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void testConstructor_nullStore_throwsException() {
        assertThatThrownBy(() -> new EmbeddingStoreLongTermMemory(null, embeddingModel))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConstructor_nullModel_throwsException() {
        assertThatThrownBy(() -> new EmbeddingStoreLongTermMemory(embeddingStore, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // store()
    // ========================

    @Test
    void testStore_callsEmbedAndAddOnStore() {
        MemoryEntry e = entry("AI trends analysis", "Researcher");

        ltm.store(e);

        verify(embeddingModel).embed("AI trends analysis");
        verify(embeddingStore).add(any(Embedding.class), any(TextSegment.class));
    }

    @Test
    void testStore_nullEntry_throwsException() {
        assertThatThrownBy(() -> ltm.store(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testStore_multipleEntries_callsAddForEach() {
        ltm.store(entry("Content A", "Agent A"));
        ltm.store(entry("Content B", "Agent B"));

        verify(embeddingModel, times(2)).embed(anyString());
        verify(embeddingStore, times(2)).add(any(Embedding.class), any(TextSegment.class));
    }

    // ========================
    // retrieve()
    // ========================

    @Test
    void testRetrieve_withResults_returnsMemoryEntries() {
        Embedding queryEmbedding = Embedding.from(DUMMY_VECTOR);
        when(embeddingModel.embed("Research AI trends")).thenReturn(Response.from(queryEmbedding));

        // Build a realistic TextSegment with metadata
        dev.langchain4j.data.document.Metadata meta = dev.langchain4j.data.document.Metadata.from(
                        MemoryEntry.META_AGENT_ROLE, "Researcher")
                .put(MemoryEntry.META_TASK_DESCRIPTION, "Research AI trends")
                .put("storedAt", "2026-01-15T10:00:00Z");
        TextSegment segment = TextSegment.from("Past research on AI", meta);
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", Embedding.from(DUMMY_VECTOR), segment);
        EmbeddingSearchResult<TextSegment> result = new EmbeddingSearchResult<>(List.of(match));

        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(result);

        List<MemoryEntry> entries = ltm.retrieve("Research AI trends", 5);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).isEqualTo("Past research on AI");
        assertThat(entries.get(0).getMeta(MemoryEntry.META_AGENT_ROLE)).isEqualTo("Researcher");
        assertThat(entries.get(0).getMeta(MemoryEntry.META_TASK_DESCRIPTION)).isEqualTo("Research AI trends");
    }

    @Test
    void testRetrieve_emptyResults_returnsEmptyList() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        List<MemoryEntry> entries = ltm.retrieve("some query", 5);

        assertThat(entries).isEmpty();
    }

    @Test
    void testRetrieve_nullQuery_returnsEmptyList() {
        List<MemoryEntry> entries = ltm.retrieve(null, 5);

        assertThat(entries).isEmpty();
    }

    @Test
    void testRetrieve_blankQuery_returnsEmptyList() {
        List<MemoryEntry> entries = ltm.retrieve("   ", 5);

        assertThat(entries).isEmpty();
    }

    @Test
    void testRetrieve_passesMaxResultsToStore() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        ltm.retrieve("query", 3);

        verify(embeddingStore).search(any(EmbeddingSearchRequest.class));
        // Verify embed was called for the query
        verify(embeddingModel).embed("query");
    }
}
