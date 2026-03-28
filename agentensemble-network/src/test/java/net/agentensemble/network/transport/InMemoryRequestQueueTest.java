package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryRequestQueue}.
 */
class InMemoryRequestQueueTest {

    private final RequestQueue queue = RequestQueue.inMemory();

    // ========================
    // Factory
    // ========================

    @Test
    void inMemory_factoryReturnsInstance() {
        assertThat(RequestQueue.inMemory()).isNotNull().isInstanceOf(InMemoryRequestQueue.class);
    }

    // ========================
    // Enqueue / dequeue
    // ========================

    @Test
    void enqueue_then_dequeue_returnsRequest() {
        WorkRequest request = workRequest("req-1", "prepare-meal");

        queue.enqueue("kitchen", request);
        WorkRequest result = queue.dequeue("kitchen", Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.task()).isEqualTo("prepare-meal");
    }

    @Test
    void dequeue_emptyQueue_returnsNullAfterTimeout() {
        WorkRequest result = queue.dequeue("empty-queue", Duration.ofMillis(50));
        assertThat(result).isNull();
    }

    @Test
    void enqueue_multipleQueues_independent() {
        WorkRequest kitchenReq = workRequest("req-k", "cook");
        WorkRequest maintReq = workRequest("req-m", "repair");

        queue.enqueue("kitchen", kitchenReq);
        queue.enqueue("maintenance", maintReq);

        WorkRequest fromKitchen = queue.dequeue("kitchen", Duration.ofSeconds(1));
        WorkRequest fromMaint = queue.dequeue("maintenance", Duration.ofSeconds(1));

        assertThat(fromKitchen.requestId()).isEqualTo("req-k");
        assertThat(fromMaint.requestId()).isEqualTo("req-m");

        // Other queue is now empty
        assertThat(queue.dequeue("kitchen", Duration.ofMillis(10))).isNull();
    }

    @Test
    void dequeue_fifoOrder() {
        queue.enqueue("q", workRequest("first", "task"));
        queue.enqueue("q", workRequest("second", "task"));
        queue.enqueue("q", workRequest("third", "task"));

        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("first");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("second");
        assertThat(queue.dequeue("q", Duration.ofSeconds(1)).requestId()).isEqualTo("third");
    }

    // ========================
    // Acknowledge
    // ========================

    @Test
    void acknowledge_noOp_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> queue.acknowledge("queue", "req-1"));
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void enqueue_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> queue.enqueue(null, workRequest("req-1", "task")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void enqueue_nullRequest_throwsNPE() {
        assertThatThrownBy(() -> queue.enqueue("q", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullQueueName_throwsNPE() {
        assertThatThrownBy(() -> queue.dequeue(null, Duration.ofSeconds(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void dequeue_nullTimeout_throwsNPE() {
        assertThatThrownBy(() -> queue.dequeue("q", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_enqueueDequeue_threadSafe() throws Exception {
        int messageCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Producers
            for (int i = 0; i < messageCount; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    queue.enqueue("concurrent", workRequest("req-" + idx, "task"));
                });
            }

            // Consumers
            for (int i = 0; i < messageCount; i++) {
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    WorkRequest req = queue.dequeue("concurrent", Duration.ofSeconds(5));
                    if (req != null) {
                        received.add(req.requestId());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received).hasSize(messageCount);
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
