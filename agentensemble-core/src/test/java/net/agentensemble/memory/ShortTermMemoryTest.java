package net.agentensemble.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShortTermMemoryTest {

    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemory();
    }

    private MemoryEntry entry(String content, String role) {
        return MemoryEntry.builder()
                .content(content)
                .agentRole(role)
                .taskDescription("task")
                .timestamp(Instant.now())
                .build();
    }

    // ========================
    // Initial state
    // ========================

    @Test
    void testNewMemory_isEmpty() {
        assertThat(memory.isEmpty()).isTrue();
        assertThat(memory.size()).isZero();
        assertThat(memory.getEntries()).isEmpty();
    }

    // ========================
    // add()
    // ========================

    @Test
    void testAdd_singleEntry_entryIsRetrievable() {
        MemoryEntry e = entry("Research result", "Researcher");
        memory.add(e);

        assertThat(memory.isEmpty()).isFalse();
        assertThat(memory.size()).isEqualTo(1);
        assertThat(memory.getEntries()).containsExactly(e);
    }

    @Test
    void testAdd_multipleEntries_orderedByInsertion() {
        MemoryEntry e1 = entry("First", "A");
        MemoryEntry e2 = entry("Second", "B");
        MemoryEntry e3 = entry("Third", "C");
        memory.add(e1);
        memory.add(e2);
        memory.add(e3);

        assertThat(memory.getEntries()).containsExactly(e1, e2, e3);
    }

    @Test
    void testAdd_nullEntry_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> memory.add(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // getEntries()
    // ========================

    @Test
    void testGetEntries_returnsUnmodifiableView() {
        memory.add(entry("content", "Agent"));
        var entries = memory.getEntries();

        assertThatThrownBy(() -> entries.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testGetEntries_addAfterGet_viewReflectsNewEntries() {
        MemoryEntry e1 = entry("first", "A");
        memory.add(e1);
        var entries = memory.getEntries();

        MemoryEntry e2 = entry("second", "B");
        memory.add(e2);

        // The unmodifiable view is backed by the live list
        assertThat(entries).hasSize(2);
    }

    // ========================
    // clear()
    // ========================

    @Test
    void testClear_afterAddingEntries_isEmpty() {
        memory.add(entry("content", "Agent"));
        memory.clear();

        assertThat(memory.isEmpty()).isTrue();
        assertThat(memory.size()).isZero();
    }

    // ========================
    // size()
    // ========================

    @Test
    void testSize_tracksCount() {
        assertThat(memory.size()).isZero();
        memory.add(entry("a", "A"));
        assertThat(memory.size()).isEqualTo(1);
        memory.add(entry("b", "B"));
        assertThat(memory.size()).isEqualTo(2);
    }
}
