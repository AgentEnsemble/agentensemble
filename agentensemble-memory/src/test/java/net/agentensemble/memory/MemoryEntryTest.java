package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryEntryTest {

    @Test
    void testBuilder_allFields_setsCorrectly() {
        Instant now = Instant.now();
        Map<String, String> metadata = Map.of(
                MemoryEntry.META_AGENT_ROLE, "Researcher",
                MemoryEntry.META_TASK_DESCRIPTION, "Research AI trends");
        MemoryEntry entry = MemoryEntry.builder()
                .content("AI is transforming software development")
                .storedAt(now)
                .metadata(metadata)
                .build();

        assertThat(entry.getContent()).isEqualTo("AI is transforming software development");
        assertThat(entry.getStoredAt()).isEqualTo(now);
        assertThat(entry.getMetadata())
                .containsEntry(MemoryEntry.META_AGENT_ROLE, "Researcher")
                .containsEntry(MemoryEntry.META_TASK_DESCRIPTION, "Research AI trends");
    }

    @Test
    void testBuilder_partialFields_nullsForOmitted() {
        MemoryEntry entry = MemoryEntry.builder().content("Some content").build();

        assertThat(entry.getContent()).isEqualTo("Some content");
        assertThat(entry.getStoredAt()).isNull();
        assertThat(entry.getStructuredContent()).isNull();
        assertThat(entry.getMetadata()).isNull();
    }

    @Test
    void testBuilder_withStructuredContent_setsCorrectly() {
        record Report(String title) {}
        Report report = new Report("AI Trends 2026");

        MemoryEntry entry = MemoryEntry.builder()
                .content("AI Trends 2026")
                .structuredContent(report)
                .storedAt(Instant.now())
                .metadata(Map.of())
                .build();

        assertThat(entry.getStructuredContent()).isEqualTo(report);
    }

    @Test
    void testGetMeta_existingKey_returnsValue() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("content")
                .metadata(Map.of("key1", "value1"))
                .build();

        assertThat(entry.getMeta("key1")).isEqualTo("value1");
    }

    @Test
    void testGetMeta_missingKey_returnsNull() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("content")
                .metadata(Map.of("key1", "value1"))
                .build();

        assertThat(entry.getMeta("missing")).isNull();
    }

    @Test
    void testGetMeta_nullMetadata_returnsNull() {
        MemoryEntry entry = MemoryEntry.builder().content("content").build();

        assertThat(entry.getMeta("anyKey")).isNull();
    }

    @Test
    void testMetaConstants_values() {
        assertThat(MemoryEntry.META_AGENT_ROLE).isEqualTo("agentRole");
        assertThat(MemoryEntry.META_TASK_DESCRIPTION).isEqualTo("taskDescription");
    }

    @Test
    void testEquality_sameFields_areEqual() {
        Instant now = Instant.now();
        MemoryEntry e1 = MemoryEntry.builder()
                .content("content")
                .storedAt(now)
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "Researcher"))
                .build();
        MemoryEntry e2 = MemoryEntry.builder()
                .content("content")
                .storedAt(now)
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "Researcher"))
                .build();

        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    @Test
    void testEquality_differentContent_notEqual() {
        Instant now = Instant.now();
        MemoryEntry e1 = MemoryEntry.builder().content("A").storedAt(now).build();
        MemoryEntry e2 = MemoryEntry.builder().content("B").storedAt(now).build();

        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    void testToString_containsContent() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("test content")
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "Agent"))
                .build();

        assertThat(entry.toString()).contains("test content");
    }
}
