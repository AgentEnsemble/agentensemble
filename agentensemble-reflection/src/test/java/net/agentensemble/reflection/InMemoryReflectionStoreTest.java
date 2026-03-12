package net.agentensemble.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryReflectionStoreTest {

    private InMemoryReflectionStore store;
    private TaskReflection reflection;

    @BeforeEach
    void setUp() {
        store = new InMemoryReflectionStore();
        reflection = TaskReflection.ofFirstRun("Refined description", "Refined output", List.of("obs"), List.of("sug"));
    }

    @Test
    void retrieve_returnsEmptyWhenNotStored() {
        Optional<TaskReflection> result = store.retrieve("unknown-key");

        assertThat(result).isEmpty();
    }

    @Test
    void storeAndRetrieve_roundTrips() {
        store.store("task-key-1", reflection);

        Optional<TaskReflection> result = store.retrieve("task-key-1");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(reflection);
    }

    @Test
    void store_replacesExistingEntry() {
        TaskReflection first = TaskReflection.ofFirstRun("d1", "o1", List.of(), List.of());
        TaskReflection second = TaskReflection.fromPrior("d2", "o2", List.of(), List.of(), first);

        store.store("key", first);
        store.store("key", second);

        assertThat(store.retrieve("key")).contains(second);
    }

    @Test
    void differentKeys_storeIndependently() {
        TaskReflection r1 = TaskReflection.ofFirstRun("d1", "o1", List.of(), List.of());
        TaskReflection r2 = TaskReflection.ofFirstRun("d2", "o2", List.of(), List.of());

        store.store("key1", r1);
        store.store("key2", r2);

        assertThat(store.retrieve("key1")).contains(r1);
        assertThat(store.retrieve("key2")).contains(r2);
    }

    @Test
    void size_reflectsNumberOfDistinctKeys() {
        assertThat(store.size()).isZero();

        store.store("key1", reflection);
        assertThat(store.size()).isEqualTo(1);

        store.store("key2", reflection);
        assertThat(store.size()).isEqualTo(2);

        // Replace existing key -- count stays same
        store.store("key1", reflection);
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void clear_removesAllEntries() {
        store.store("key1", reflection);
        store.store("key2", reflection);

        store.clear();

        assertThat(store.size()).isZero();
        assertThat(store.retrieve("key1")).isEmpty();
        assertThat(store.retrieve("key2")).isEmpty();
    }

    // --- store() validation ---

    @Test
    void store_rejectsNullKey() {
        assertThatNullPointerException()
                .isThrownBy(() -> store.store(null, reflection))
                .withMessageContaining("taskIdentity");
    }

    @Test
    void store_rejectsBlankKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.store("  ", reflection))
                .withMessageContaining("taskIdentity");
    }

    @Test
    void store_rejectsNullReflection() {
        assertThatNullPointerException()
                .isThrownBy(() -> store.store("key", null))
                .withMessageContaining("reflection");
    }

    // --- retrieve() validation ---

    @Test
    void retrieve_rejectsNullKey() {
        assertThatNullPointerException().isThrownBy(() -> store.retrieve(null)).withMessageContaining("taskIdentity");
    }

    @Test
    void retrieve_rejectsBlankKey() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.retrieve(""))
                .withMessageContaining("taskIdentity");
    }

    @Test
    void store_isThreadSafe() throws InterruptedException {
        int threadCount = 20;
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                TaskReflection r = TaskReflection.ofFirstRun("desc-" + index, "output", List.of(), List.of());
                store.store("key-" + index, r);
            });
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(store.size()).isEqualTo(threadCount);
    }
}
