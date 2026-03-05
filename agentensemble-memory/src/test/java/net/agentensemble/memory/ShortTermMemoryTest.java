package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShortTermMemoryTest {

    private ShortTermMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ShortTermMemory();
    }

    private MemoryEntry entry(String content, String role) {
        return MemoryEntry.builder()
                .content(content)
                .storedAt(Instant.now())
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, role, MemoryEntry.META_TASK_DESCRIPTION, "task"))
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
        assertThatThrownBy(() -> memory.add(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // getEntries()
    // ========================

    @Test
    void testGetEntries_returnsUnmodifiableView() {
        memory.add(entry("content", "Agent"));
        var entries = memory.getEntries();

        assertThatThrownBy(() -> entries.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testGetEntries_isSnapshot_doesNotReflectSubsequentWrites() {
        MemoryEntry e1 = entry("first", "A");
        memory.add(e1);
        List<MemoryEntry> snapshot = memory.getEntries();

        MemoryEntry e2 = entry("second", "B");
        memory.add(e2);

        // getEntries() returns a snapshot (List.copyOf); adding after the call does not affect it
        assertThat(snapshot).hasSize(1).containsExactly(e1);
        // A fresh call reflects the new entry
        assertThat(memory.getEntries()).hasSize(2);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void testConcurrentAdd_allEntriesRecorded() throws InterruptedException {
        int threadCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                var unused = executor.submit(() -> {
                    try {
                        start.await();
                        memory.add(entry("entry-" + idx, "Agent-" + idx));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }

        assertThat(memory.size()).isEqualTo(threadCount);
        assertThat(memory.getEntries()).hasSize(threadCount);
    }

    @Test
    void testConcurrentAddAndRead_noExceptionThrown() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount * 2);
        List<Exception> errors = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Writer threads
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                var unused = executor.submit(() -> {
                    try {
                        start.await();
                        memory.add(entry("entry-" + idx, "Agent-" + idx));
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            // Reader threads
            for (int i = 0; i < threadCount; i++) {
                var unused2 = executor.submit(() -> {
                    try {
                        start.await();
                        // Reading while writers are active must not throw
                        memory.getEntries();
                        memory.size();
                        memory.isEmpty();
                    } catch (Exception e) {
                        errors.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        }

        assertThat(errors).isEmpty();
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
