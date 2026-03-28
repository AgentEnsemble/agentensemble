package net.agentensemble.transport.redis;

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
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link RedisResultStore} using Testcontainers.
 */
@Testcontainers
class RedisResultStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private io.lettuce.core.RedisClient redisClient;
    private RedisResultStore store;

    @BeforeEach
    void setUp() {
        redisClient =
                io.lettuce.core.RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        store = RedisResultStore.create(redisClient);

        // Flush Redis between tests for isolation
        try (var conn = redisClient.connect()) {
            conn.sync().flushall();
        }
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    // ========================
    // Store / retrieve
    // ========================

    @Test
    void store_then_retrieve_roundTrip() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "output", null, 500L);
        store.store("req-1", response, Duration.ofHours(1));

        WorkResponse retrieved = store.retrieve("req-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-1");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("output");
        assertThat(retrieved.durationMs()).isEqualTo(500L);
    }

    @Test
    void retrieve_unknown_returnsNull() {
        WorkResponse retrieved = store.retrieve("nonexistent");
        assertThat(retrieved).isNull();
    }

    @Test
    void store_overwritesPrevious() {
        store.store("req-1", new WorkResponse("req-1", "FAILED", null, "error", 100L), Duration.ofHours(1));
        store.store("req-1", new WorkResponse("req-1", "COMPLETED", "retry-ok", null, 200L), Duration.ofHours(1));

        WorkResponse retrieved = store.retrieve("req-1");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("retry-ok");
    }

    // ========================
    // TTL
    // ========================

    @Test
    void store_withTtl_expiresAutomatically() throws Exception {
        store.store("req-ttl", new WorkResponse("req-ttl", "COMPLETED", "temp", null, 10L), Duration.ofSeconds(1));

        // Should be present immediately
        assertThat(store.retrieve("req-ttl")).isNotNull();

        // Wait for TTL to expire
        Thread.sleep(1500);

        assertThat(store.retrieve("req-ttl")).isNull();
    }

    // ========================
    // Subscribe (Pub/Sub)
    // ========================

    @Test
    void subscribe_then_store_callbackFired() throws Exception {
        AtomicReference<WorkResponse> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        store.subscribe("req-sub", response -> {
            captured.set(response);
            latch.countDown();
        });

        // subscribe() is synchronous -- the SUBSCRIBE ack has been received, so we can
        // publish immediately without a sleep.
        store.store("req-sub", new WorkResponse("req-sub", "COMPLETED", "done", null, 100L), Duration.ofHours(1));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().requestId()).isEqualTo("req-sub");
        assertThat(captured.get().status()).isEqualTo("COMPLETED");
    }

    @Test
    void subscribe_storeBeforeSubscribe_stillDelivered() throws Exception {
        // Store the result BEFORE subscribing
        store.store("req-race", new WorkResponse("req-race", "COMPLETED", "early", null, 50L), Duration.ofHours(1));

        AtomicReference<WorkResponse> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Subscribe after the result is already stored -- subscribe() checks for
        // an already-stored result per the SPI contract and invokes the callback.
        store.subscribe("req-race", response -> {
            captured.set(response);
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().requestId()).isEqualTo("req-race");
        assertThat(captured.get().result()).isEqualTo("early");
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void store_nullRequestId_throwsNPE() {
        assertThatThrownBy(() ->
                        store.store(null, new WorkResponse("r", "COMPLETED", null, null, null), Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void store_nullResponse_throwsNPE() {
        assertThatThrownBy(() -> store.store("req-1", null, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void store_nullTtl_throwsNPE() {
        assertThatThrownBy(() -> store.store("req-1", new WorkResponse("r", "COMPLETED", null, null, null), null))
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
        int count = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> retrievedIds = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Store all first
            for (int i = 0; i < count; i++) {
                store.store(
                        "req-" + i,
                        new WorkResponse("req-" + i, "COMPLETED", "result-" + i, null, (long) i),
                        Duration.ofHours(1));
            }

            // Retrieve concurrently
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    WorkResponse result = store.retrieve("req-" + idx);
                    if (result != null) {
                        retrievedIds.add(result.requestId());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(retrievedIds).hasSize(count);
    }

    // ========================
    // Key scheme
    // ========================

    @Test
    void resultKey_includesPrefix() {
        assertThat(RedisResultStore.resultKey("req-1")).isEqualTo("agentensemble:result:req-1");
    }

    @Test
    void notifyChannel_includesPrefix() {
        assertThat(RedisResultStore.notifyChannel("req-1")).isEqualTo("agentensemble:notify:req-1");
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
