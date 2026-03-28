package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryResultStore}.
 */
class InMemoryResultStoreTest {

    private static final Duration TTL = Duration.ofHours(1);

    private final ResultStore store = ResultStore.inMemory();

    // ========================
    // Factory
    // ========================

    @Test
    void inMemory_factoryReturnsInstance() {
        assertThat(ResultStore.inMemory()).isNotNull().isInstanceOf(InMemoryResultStore.class);
    }

    // ========================
    // Store / retrieve
    // ========================

    @Test
    void store_then_retrieve_returnsResponse() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "output", null, 100L);

        store.store("req-1", response, TTL);
        WorkResponse result = store.retrieve("req-1");

        assertThat(result).isNotNull();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("output");
    }

    @Test
    void retrieve_unknown_returnsNull() {
        assertThat(store.retrieve("nonexistent")).isNull();
    }

    @Test
    void store_overwritesPrevious() {
        store.store("req-1", new WorkResponse("req-1", "FAILED", null, "error", 50L), TTL);
        store.store("req-1", new WorkResponse("req-1", "COMPLETED", "success", null, 200L), TTL);

        WorkResponse result = store.retrieve("req-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("success");
    }

    // ========================
    // Subscribe
    // ========================

    @Test
    void subscribe_then_store_callbackFired() {
        AtomicReference<WorkResponse> captured = new AtomicReference<>();

        store.subscribe("req-1", captured::set);
        store.store("req-1", new WorkResponse("req-1", "COMPLETED", "done", null, 100L), TTL);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().requestId()).isEqualTo("req-1");
        assertThat(captured.get().result()).isEqualTo("done");
    }

    @Test
    void subscribe_secondSubscribe_replacesFirst() {
        List<String> fired = Collections.synchronizedList(new ArrayList<>());

        store.subscribe("req-1", r -> fired.add("callback-1"));
        store.subscribe("req-1", r -> fired.add("callback-2"));

        store.store("req-1", new WorkResponse("req-1", "COMPLETED", "done", null, 100L), TTL);

        // Second subscribe replaces first -- only the latest callback fires.
        assertThat(fired).containsExactly("callback-2");
    }

    @Test
    void subscribe_afterStore_callbackFiredImmediately() {
        store.store("req-1", new WorkResponse("req-1", "COMPLETED", "done", null, 100L), TTL);

        AtomicReference<WorkResponse> captured = new AtomicReference<>();
        store.subscribe("req-1", captured::set);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().requestId()).isEqualTo("req-1");
        assertThat(captured.get().result()).isEqualTo("done");
    }

    @Test
    void subscribe_concurrentStoreRace_callbackFiredExactlyOnce() throws Exception {
        // Regression test: prior implementation could invoke the callback twice when
        // store() and subscribe() raced concurrently.
        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            ResultStore localStore = ResultStore.inMemory();
            String requestId = "req-race-" + i;
            AtomicInteger callbackCount = new AtomicInteger();
            WorkResponse response = new WorkResponse(requestId, "COMPLETED", "done", null, 100L);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Thread subscriber = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitQuietly(go);
                localStore.subscribe(requestId, r -> callbackCount.incrementAndGet());
            });
            Thread storer = Thread.ofVirtual().start(() -> {
                ready.countDown();
                awaitQuietly(go);
                localStore.store(requestId, response, TTL);
            });

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            subscriber.join(5_000);
            storer.join(5_000);

            assertThat(callbackCount.get())
                    .as("Iteration %d: callback must fire exactly once", i)
                    .isEqualTo(1);
        }
    }

    @Test
    void subscribe_differentRequestId_notFired() {
        AtomicReference<WorkResponse> captured = new AtomicReference<>();

        store.subscribe("req-1", captured::set);
        store.store("req-2", new WorkResponse("req-2", "COMPLETED", "other", null, 100L), TTL);

        assertThat(captured.get()).isNull();
    }

    // ========================
    // TTL expiration
    // ========================

    @Test
    void retrieve_afterTtlExpires_returnsNull() throws Exception {
        store.store("req-ttl", new WorkResponse("req-ttl", "COMPLETED", "done", null, 100L), Duration.ofMillis(50));
        assertThat(store.retrieve("req-ttl")).isNotNull();

        Thread.sleep(100);

        assertThat(store.retrieve("req-ttl")).isNull();
    }

    @Test
    void retrieve_beforeTtlExpires_returnsResponse() {
        store.store("req-ttl2", new WorkResponse("req-ttl2", "COMPLETED", "done", null, 100L), Duration.ofHours(1));

        assertThat(store.retrieve("req-ttl2")).isNotNull();
        assertThat(store.retrieve("req-ttl2").requestId()).isEqualTo("req-ttl2");
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void store_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> store.store(null, new WorkResponse("r", "OK", null, null, null), TTL))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void store_nullResponse_throwsNPE() {
        assertThatThrownBy(() -> store.store("req-1", null, TTL)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void store_nullTtl_throwsNPE() {
        assertThatThrownBy(() -> store.store("req-1", new WorkResponse("r", "OK", null, null, null), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void retrieve_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> store.retrieve(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void subscribe_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> store.subscribe(null, r -> {})).isInstanceOf(NullPointerException.class);
    }

    @Test
    void subscribe_nullCallback_throwsNPE() {
        assertThatThrownBy(() -> store.subscribe("req-1", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_storeRetrieve_threadSafe() throws Exception {
        int count = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<WorkResponse> retrieved = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Producers
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    store.store(
                            "req-" + idx,
                            new WorkResponse("req-" + idx, "COMPLETED", "result-" + idx, null, (long) idx),
                            TTL);
                });
            }

            // Consumers (slight delay to allow some stores to complete)
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    // Small busy-wait to let stores land
                    WorkResponse r = null;
                    for (int attempt = 0; attempt < 50 && r == null; attempt++) {
                        r = store.retrieve("req-" + idx);
                        if (r == null) {
                            Thread.yield();
                        }
                    }
                    if (r != null) {
                        retrieved.add(r);
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // At least some should have been retrieved (timing-dependent, but with 50 retries very likely all)
        assertThat(retrieved).isNotEmpty();
    }

    // ========================
    // Helpers
    // ========================

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
