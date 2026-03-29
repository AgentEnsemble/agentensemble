package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import net.agentensemble.memory.EvictionPolicy;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemory} with {@link Consistency#LOCKED}.
 */
class SharedMemoryLockedTest {

    private SharedMemory sharedMemory;

    @BeforeEach
    void setUp() {
        sharedMemory = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.LOCKED)
                .lockProvider(LockProvider.inMemory())
                .build();
    }

    @Test
    void storeAndRetrieve_acquiresLock() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("locked content")
                .storedAt(Instant.now())
                .build();

        sharedMemory.store("scope1", entry);

        List<MemoryEntry> results = sharedMemory.retrieve("scope1", null, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("locked content");
    }

    @Test
    void twoThreads_sameScopeSerializeCorrectly() throws InterruptedException {
        int threadCount = 2;
        int writesPerThread = 50;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < writesPerThread; i++) {
                                MemoryEntry entry = MemoryEntry.builder()
                                        .content("thread-" + threadId + "-entry-" + i)
                                        .storedAt(Instant.now())
                                        .build();
                                sharedMemory.store("shared-scope", entry);
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        } finally {
                            latch.countDown();
                        }
                    })
                    .start();
        }

        latch.await();
        assertThat(errors).isEmpty();

        List<MemoryEntry> results = sharedMemory.retrieve("shared-scope", null, 200);
        assertThat(results).hasSize(threadCount * writesPerThread);
    }

    @Test
    void retrieve_acquiresLock_returnsCorrectData() {
        MemoryEntry entry = MemoryEntry.builder()
                .content("content under lock")
                .storedAt(Instant.now())
                .build();
        sharedMemory.store("scope1", entry);

        // Multiple retrieves should work correctly under locking
        List<MemoryEntry> results1 = sharedMemory.retrieve("scope1", null, 10);
        List<MemoryEntry> results2 = sharedMemory.retrieve("scope1", null, 10);

        assertThat(results1).hasSize(1);
        assertThat(results2).hasSize(1);
        assertThat(results1.get(0).getContent()).isEqualTo("content under lock");
    }

    @Test
    void evict_acquiresLockAndAppliesPolicy() {
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
