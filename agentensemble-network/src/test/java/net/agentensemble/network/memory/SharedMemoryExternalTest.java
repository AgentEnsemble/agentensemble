package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemory} with {@link Consistency#EXTERNAL}.
 */
class SharedMemoryExternalTest {

    private SharedMemory sharedMemory;

    @BeforeEach
    void setUp() {
        sharedMemory = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EXTERNAL)
                .build();
    }

    @Test
    void storeAndRetrieve_passThrough() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("external content")
                .storedAt(Instant.now())
                .build();

        sharedMemory.store("scope1", entry);

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("external content");
    }

    @Test
    void storeWithVersion_fallsBackToRegularStore() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("versioned on external")
                .storedAt(Instant.now())
                .build();

        // Should not throw even though version is provided, because EXTERNAL ignores versioning
        sharedMemory.store("scope1", entry, 42);

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("versioned on external");
    }

    @Test
    void multipleEntries_allRetrieved() {
        for (int i = 0; i < 5; i++) {
            MemoryEntry entry = MemoryEntry.builder()
                    .content("entry-" + i)
                    .storedAt(Instant.now())
                    .build();
            sharedMemory.store("scope1", entry);
        }

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(5);
    }
}
