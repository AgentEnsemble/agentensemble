package net.agentensemble.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEntryTest {

    @Test
    void testBuilder_allFields_setsCorrectly() {
        Instant now = Instant.now();
        MemoryEntry entry = MemoryEntry.builder()
                .content("AI is transforming software development")
                .agentRole("Researcher")
                .taskDescription("Research AI trends")
                .timestamp(now)
                .build();

        assertThat(entry.getContent()).isEqualTo("AI is transforming software development");
        assertThat(entry.getAgentRole()).isEqualTo("Researcher");
        assertThat(entry.getTaskDescription()).isEqualTo("Research AI trends");
        assertThat(entry.getTimestamp()).isEqualTo(now);
    }

    @Test
    void testBuilder_partialFields_nullsForOmitted() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("Some content")
                .build();

        assertThat(entry.getContent()).isEqualTo("Some content");
        assertThat(entry.getAgentRole()).isNull();
        assertThat(entry.getTaskDescription()).isNull();
        assertThat(entry.getTimestamp()).isNull();
    }

    @Test
    void testEquality_sameFields_areEqual() {
        Instant now = Instant.now();
        MemoryEntry e1 = MemoryEntry.builder()
                .content("content")
                .agentRole("Researcher")
                .taskDescription("task")
                .timestamp(now)
                .build();
        MemoryEntry e2 = MemoryEntry.builder()
                .content("content")
                .agentRole("Researcher")
                .taskDescription("task")
                .timestamp(now)
                .build();

        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    @Test
    void testEquality_differentContent_notEqual() {
        Instant now = Instant.now();
        MemoryEntry e1 = MemoryEntry.builder().content("A").timestamp(now).build();
        MemoryEntry e2 = MemoryEntry.builder().content("B").timestamp(now).build();

        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    void testToString_containsContent() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("test content")
                .agentRole("Agent")
                .build();

        assertThat(entry.toString()).contains("test content");
    }
}
