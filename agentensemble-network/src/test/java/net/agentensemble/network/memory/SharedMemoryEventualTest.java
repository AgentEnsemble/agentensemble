package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import net.agentensemble.memory.EvictionPolicy;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemory} with {@link Consistency#EVENTUAL}.
 */
class SharedMemoryEventualTest {

    private SharedMemory sharedMemory;

    @BeforeEach
    void setUp() {
        sharedMemory = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EVENTUAL)
                .build();
    }

    @Test
    void storeAndRetrieve_passThrough() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("test content")
                .storedAt(Instant.now())
                .build();

        sharedMemory.store("scope1", entry);

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("test content");
    }

    @Test
    void multipleWrites_accumulate() {
        MemoryEntry entry1 =
                MemoryEntry.builder().content("first").storedAt(Instant.now()).build();
        MemoryEntry entry2 =
                MemoryEntry.builder().content("second").storedAt(Instant.now()).build();
        MemoryEntry entry3 =
                MemoryEntry.builder().content("third").storedAt(Instant.now()).build();

        sharedMemory.store("scope1", entry1);
        sharedMemory.store("scope1", entry2);
        sharedMemory.store("scope1", entry3);

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(3);
        assertThat(results).extracting(MemoryEntry::getContent).containsExactly("first", "second", "third");
    }

    @Test
    void retrieve_emptyScope_returnsEmptyList() {
        List<MemoryEntry> results = sharedMemory.retrieve("empty", null, 10);
        assertThat(results).isEmpty();
    }

    @Test
    void store_accessorReturnsUnderlyingStore() {
        assertThat(sharedMemory.store()).isNotNull();
    }

    @Test
    void evict_appliesPolicy() {
        for (int i = 0; i < 5; i++) {
            MemoryEntry entry = MemoryEntry.builder()
                    .content("entry-" + i)
                    .storedAt(Instant.now())
                    .build();
            sharedMemory.store("scope1", entry);
        }

        sharedMemory.evict("scope1", EvictionPolicy.keepLastEntries(2));

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(2);
    }
}
