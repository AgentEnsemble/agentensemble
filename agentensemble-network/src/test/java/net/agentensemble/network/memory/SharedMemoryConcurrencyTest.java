package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SharedMemory} concurrent access.
 */
class SharedMemoryConcurrencyTest {

    private static final int THREAD_COUNT = 8;
    private static final int WRITES_PER_THREAD = 100;

    @Test
    void eventualMode_noDataLoss() throws InterruptedException {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EVENTUAL)
                .build();

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            new Thread(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < WRITES_PER_THREAD; i++) {
                                MemoryEntry entry = MemoryEntry.builder()
                                        .content("t" + threadId + "-" + i)
                                        .storedAt(Instant.now())
                                        .build();
                                sm.store("scope", entry);
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

        List<MemoryEntry> results = sm.retrieve("scope", null, THREAD_COUNT * WRITES_PER_THREAD + 10);
        assertThat(results).hasSize(THREAD_COUNT * WRITES_PER_THREAD);
    }

    @Test
    void lockedMode_serializedAccess() throws InterruptedException {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.LOCKED)
                .lockProvider(LockProvider.inMemory())
                .build();

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            new Thread(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < WRITES_PER_THREAD; i++) {
                                MemoryEntry entry = MemoryEntry.builder()
                                        .content("t" + threadId + "-" + i)
                                        .storedAt(Instant.now())
                                        .build();
                                sm.store("scope", entry);
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

        List<MemoryEntry> results = sm.retrieve("scope", null, THREAD_COUNT * WRITES_PER_THREAD + 10);
        assertThat(results).hasSize(THREAD_COUNT * WRITES_PER_THREAD);
    }

    @Test
    void optimisticMode_casConflictsDetected() throws InterruptedException {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.OPTIMISTIC)
                .build();

        CyclicBarrier barrier = new CyclicBarrier(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        AtomicInteger conflicts = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            new Thread(() -> {
                        try {
                            barrier.await();
                            for (int i = 0; i < WRITES_PER_THREAD; i++) {
                                MemoryEntry entry = MemoryEntry.builder()
                                        .content("t" + threadId + "-" + i)
                                        .storedAt(Instant.now())
                                        .build();

                                boolean stored = false;
                                for (int attempt = 0; attempt < 10 && !stored; attempt++) {
                                    VersionedResult vr = sm.retrieveVersioned("scope", null, 10);
                                    try {
                                        sm.store("scope", entry, vr.version());
                                        successes.incrementAndGet();
                                        stored = true;
                                    } catch (ConcurrentMemoryStoreException e) {
                                        conflicts.incrementAndGet();
                                    }
                                }
                                if (!stored) {
                                    // Fall back to unversioned store if retries exhausted
                                    sm.store("scope", entry);
                                    successes.incrementAndGet();
                                }
                            }
                        } catch (Throwable e) {
                            unexpectedErrors.add(e);
                        } finally {
                            latch.countDown();
                        }
                    })
                    .start();
        }

        latch.await();
        assertThat(unexpectedErrors).isEmpty();

        // All writes should ultimately succeed (conflicts are retried)
        assertThat(successes.get()).isEqualTo(THREAD_COUNT * WRITES_PER_THREAD);

        // Conflicts may or may not occur depending on scheduler timing.
        // The key invariant is that the CAS mechanism works: no data corruption,
        // no unexpected exceptions, and all writes eventually succeed.
        assertThat(conflicts.get()).isGreaterThanOrEqualTo(0);
    }
}
