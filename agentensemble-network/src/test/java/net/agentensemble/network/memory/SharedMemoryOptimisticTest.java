package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemory} with {@link Consistency#OPTIMISTIC}.
 */
class SharedMemoryOptimisticTest {

    private SharedMemory sharedMemory;

    @BeforeEach
    void setUp() {
        sharedMemory = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.OPTIMISTIC)
                .build();
    }

    @Test
    void retrieveVersioned_returnsVersion() {
        VersionedResult result = sharedMemory.retrieveVersioned("scope1", null, 10);
        assertThat(result.version()).isZero();
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void storeWithCorrectVersion_succeedsAndIncrementsVersion() {
        VersionedResult result = sharedMemory.retrieveVersioned("scope1", null, 10);
        assertThat(result.version()).isZero();

        MemoryEntry entry = MemoryEntry.builder()
                .content("optimistic write")
                .storedAt(Instant.now())
                .build();

        sharedMemory.store("scope1", entry, result.version());

        VersionedResult updated = sharedMemory.retrieveVersioned("scope1", null, 10);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.entries()).hasSize(1);
        assertThat(updated.entries().get(0).getContent()).isEqualTo("optimistic write");
    }

    @Test
    void storeWithStaleVersion_throwsConcurrentMemoryStoreException() {
        MemoryEntry entry1 =
                MemoryEntry.builder().content("first").storedAt(Instant.now()).build();

        // Store at version 0 -> version becomes 1
        sharedMemory.store("scope1", entry1, 0);

        // Trying to store at version 0 again should fail
        MemoryEntry entry2 =
                MemoryEntry.builder().content("second").storedAt(Instant.now()).build();

        assertThatThrownBy(() -> sharedMemory.store("scope1", entry2, 0))
                .isInstanceOf(ConcurrentMemoryStoreException.class)
                .satisfies(ex -> {
                    ConcurrentMemoryStoreException cmse = (ConcurrentMemoryStoreException) ex;
                    assertThat(cmse.scope()).isEqualTo("scope1");
                    assertThat(cmse.expectedVersion()).isZero();
                });
    }

    @Test
    void retryLoopPattern_works() {
        // Simulate a successful retry after conflict
        MemoryEntry entry1 =
                MemoryEntry.builder().content("initial").storedAt(Instant.now()).build();
        sharedMemory.store("scope1", entry1, 0);

        // Attempt with stale version (should fail)
        MemoryEntry entry2 =
                MemoryEntry.builder().content("retry").storedAt(Instant.now()).build();

        boolean stored = false;
        for (int attempt = 0; attempt < 3 && !stored; attempt++) {
            VersionedResult result = sharedMemory.retrieveVersioned("scope1", null, 10);
            try {
                sharedMemory.store("scope1", entry2, result.version());
                stored = true;
            } catch (ConcurrentMemoryStoreException e) {
                // retry
            }
        }
        assertThat(stored).isTrue();

        List<MemoryEntry> entries = sharedMemory.retrieve("scope1", null, 10);
        assertThat(entries).hasSize(2);
    }

    @Test
    void storeWithoutVersion_incrementsVersionAutomatically() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("auto-versioned")
                .storedAt(Instant.now())
                .build();

        sharedMemory.store("scope1", entry);

        VersionedResult result = sharedMemory.retrieveVersioned("scope1", null, 10);
        assertThat(result.version()).isEqualTo(1);
    }
}
