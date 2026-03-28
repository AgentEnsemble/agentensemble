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
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for {@link RedisRequestQueue} using Testcontainers.
 */
@Testcontainers
class RedisRequestQueueTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private io.lettuce.core.RedisClient redisClient;
    private RedisRequestQueue queue;

    @BeforeEach
    void setUp() {
        redisClient =
                io.lettuce.core.RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        queue = RedisRequestQueue.create(redisClient, "test-consumer");

        // Flush Redis between tests for isolation
        try (var conn = redisClient.connect()) {
            conn.sync().flushall();
        }
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    // ========================
    // Basic send / receive
    // ========================

    @Test
    void enqueue_then_dequeue_roundTrip() {
        WorkRequest request = workRequest("req-1", "cook");
        queue.enqueue("kitchen", request);

        WorkRequest received = queue.dequeue("kitchen", Duration.ofSeconds(5));
        assertThat(received).isNotNull();
        assertThat(received.requestId()).isEqualTo("req-1");
        assertThat(received.task()).isEqualTo("cook");
    }

    @Test
    void dequeue_emptyQueue_returnsNullAfterTimeout() {
        WorkRequest received = queue.dequeue("empty-queue", Duration.ofMillis(200));
        assertThat(received).isNull();
    }

    @Test
    void dequeue_fifoOrder() {
        queue.enqueue("kitchen", workRequest("first", "task"));
        queue.enqueue("kitchen", workRequest("second", "task"));
        queue.enqueue("kitchen", workRequest("third", "task"));

        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1)).requestId()).isEqualTo("first");
        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1)).requestId()).isEqualTo("second");
        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1)).requestId()).isEqualTo("third");
    }

    @Test
    void enqueue_multipleQueues_independent() {
        queue.enqueue("kitchen", workRequest("req-k", "cook"));
        queue.enqueue("maintenance", workRequest("req-m", "repair"));

        WorkRequest fromKitchen = queue.dequeue("kitchen", Duration.ofSeconds(1));
        WorkRequest fromMaintenance = queue.dequeue("maintenance", Duration.ofSeconds(1));

        assertThat(fromKitchen.requestId()).isEqualTo("req-k");
        assertThat(fromMaintenance.requestId()).isEqualTo("req-m");
    }

    // ========================
    // Acknowledge
    // ========================

    @Test
    void acknowledge_removesFromPending() {
        queue.enqueue("kitchen", workRequest("req-ack", "cook"));
        WorkRequest received = queue.dequeue("kitchen", Duration.ofSeconds(1));
        assertThat(received).isNotNull();

        queue.acknowledge("kitchen", "req-ack");

        // Verify: a second consumer trying XAUTOCLAIM should find nothing pending
        try (RedisRequestQueue consumer2 = RedisRequestQueue.create(redisClient, "consumer-2", Duration.ofMillis(1))) {
            WorkRequest reclaimed = consumer2.dequeue("kitchen", Duration.ofMillis(200));
            assertThat(reclaimed).isNull();
        }
    }

    @Test
    void acknowledge_unknownRequestId_doesNotThrow() {
        // Should silently ignore unknown requestIds
        queue.acknowledge("kitchen", "nonexistent");
    }

    // ========================
    // Consumer group load balancing
    // ========================

    @Test
    void consumerGroup_loadBalancing() {
        // Enqueue multiple messages
        for (int i = 0; i < 4; i++) {
            queue.enqueue("kitchen", workRequest("req-" + i, "task"));
        }

        // Two consumers with different names reading from the same group
        List<String> consumer1Ids = new ArrayList<>();
        List<String> consumer2Ids = new ArrayList<>();

        try (RedisRequestQueue c2 = RedisRequestQueue.create(redisClient, "consumer-2")) {
            // Alternate dequeue between the two consumers
            WorkRequest r1 = queue.dequeue("kitchen", Duration.ofSeconds(1));
            if (r1 != null) consumer1Ids.add(r1.requestId());

            WorkRequest r2 = c2.dequeue("kitchen", Duration.ofSeconds(1));
            if (r2 != null) consumer2Ids.add(r2.requestId());

            WorkRequest r3 = queue.dequeue("kitchen", Duration.ofSeconds(1));
            if (r3 != null) consumer1Ids.add(r3.requestId());

            WorkRequest r4 = c2.dequeue("kitchen", Duration.ofSeconds(1));
            if (r4 != null) consumer2Ids.add(r4.requestId());
        }

        // All 4 messages should have been distributed (no duplicates)
        List<String> allIds = new ArrayList<>(consumer1Ids);
        allIds.addAll(consumer2Ids);
        assertThat(allIds).hasSize(4).containsExactlyInAnyOrder("req-0", "req-1", "req-2", "req-3");
    }

    // ========================
    // Visibility timeout and redelivery
    // ========================

    @Test
    void visibilityTimeout_redelivery() throws Exception {
        // Create a queue with a very short visibility timeout
        try (RedisRequestQueue shortTimeout =
                RedisRequestQueue.create(redisClient, "consumer-a", Duration.ofMillis(200))) {

            shortTimeout.enqueue("kitchen", workRequest("req-vis", "cook"));

            // Consumer A dequeues but does NOT acknowledge
            WorkRequest received = shortTimeout.dequeue("kitchen", Duration.ofSeconds(1));
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo("req-vis");
        }

        // Wait past the visibility timeout
        Thread.sleep(300);

        // Consumer B should be able to reclaim the message
        try (RedisRequestQueue consumerB =
                RedisRequestQueue.create(redisClient, "consumer-b", Duration.ofMillis(100))) {
            WorkRequest reclaimed = consumerB.dequeue("kitchen", Duration.ofSeconds(2));
            assertThat(reclaimed).isNotNull();
            assertThat(reclaimed.requestId()).isEqualTo("req-vis");

            consumerB.acknowledge("kitchen", "req-vis");
        }
    }

    // ========================
    // Pending entry recovery (same consumerName after restart)
    // ========================

    @Test
    void pendingRecovery_sameConsumerName_resumesPendingEntries() {
        // Simulate a consumer that dequeues but crashes before acknowledging
        try (RedisRequestQueue session1 = RedisRequestQueue.create(redisClient, "stable-consumer")) {
            session1.enqueue("kitchen", workRequest("req-pending", "cook"));
            WorkRequest received = session1.dequeue("kitchen", Duration.ofSeconds(1));
            assertThat(received).isNotNull();
            assertThat(received.requestId()).isEqualTo("req-pending");
            // Session 1 "crashes" without acknowledging (close without ack)
        }

        // New session with the SAME consumer name picks up the pending entry immediately
        try (RedisRequestQueue session2 = RedisRequestQueue.create(redisClient, "stable-consumer")) {
            WorkRequest resumed = session2.dequeue("kitchen", Duration.ofSeconds(2));
            assertThat(resumed).isNotNull();
            assertThat(resumed.requestId()).isEqualTo("req-pending");

            session2.acknowledge("kitchen", "req-pending");
        }
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void enqueue_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> queue.enqueue(null, workRequest("r", "t"))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void enqueue_nullRequest_throwsNPE() {
        assertThatThrownBy(() -> queue.enqueue("kitchen", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> queue.dequeue(null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullTimeout_throwsNPE() {
        assertThatThrownBy(() -> queue.dequeue("kitchen", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acknowledge_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> queue.acknowledge(null, "req-1")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void acknowledge_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> queue.acknowledge("kitchen", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_enqueueDequeue_threadSafe() throws Exception {
        int count = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> receivedIds = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Enqueue all first
            for (int i = 0; i < count; i++) {
                queue.enqueue("kitchen", workRequest("req-" + i, "task"));
            }

            // Dequeue concurrently
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    WorkRequest req = queue.dequeue("kitchen", Duration.ofSeconds(5));
                    if (req != null) {
                        receivedIds.add(req.requestId());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(receivedIds).hasSize(count);
    }

    // ========================
    // Key scheme
    // ========================

    @Test
    void streamKey_includesPrefix() {
        assertThat(RedisRequestQueue.streamKey("kitchen")).isEqualTo("agentensemble:queue:kitchen");
    }

    @Test
    void groupName_includesPrefix() {
        assertThat(RedisRequestQueue.groupName("kitchen")).isEqualTo("agentensemble:group:kitchen");
    }

    // ========================
    // Consumer group idempotent creation
    // ========================

    @Test
    void ensureConsumerGroup_idempotent_secondCallIgnoresBusyGroup() {
        // First enqueue creates the consumer group; second enqueue on same queue should not fail
        queue.enqueue("kitchen", workRequest("req-a", "task"));
        queue.enqueue("kitchen", workRequest("req-b", "task"));

        // Create a second queue instance to trigger BUSYGROUP on ensureConsumerGroup
        try (RedisRequestQueue queue2 = RedisRequestQueue.create(redisClient, "consumer-2")) {
            queue2.enqueue("kitchen", workRequest("req-c", "task"));
        }

        // All three messages should be dequeuable
        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1))).isNotNull();
        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1))).isNotNull();
        assertThat(queue.dequeue("kitchen", Duration.ofSeconds(1))).isNotNull();
    }

    // ========================
    // Consumer name
    // ========================

    @Test
    void consumerName_returnsConfiguredName() {
        assertThat(queue.consumerName()).isEqualTo("test-consumer");
    }

    @Test
    void create_defaultConsumerName_isUuid() {
        try (RedisRequestQueue defaultQueue = RedisRequestQueue.create(redisClient)) {
            assertThat(defaultQueue.consumerName()).isNotNull().isNotEmpty();
            // Should be parseable as UUID
            java.util.UUID.fromString(defaultQueue.consumerName());
        }
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
