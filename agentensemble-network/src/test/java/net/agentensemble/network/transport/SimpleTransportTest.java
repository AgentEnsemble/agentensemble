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
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SimpleTransport}.
 */
class SimpleTransportTest {

    // ========================
    // Send / receive
    // ========================

    @Test
    void send_and_receive_inProcess() {
        SimpleTransport transport = new SimpleTransport();

        WorkRequest request = workRequest("req-1", "prepare-meal");
        transport.send(request);

        // Receive from the task-named queue
        WorkRequest received = transport.requestQueue().dequeue("prepare-meal", Duration.ofSeconds(1));
        assertThat(received).isNotNull();
        assertThat(received.requestId()).isEqualTo("req-1");
    }

    @Test
    void receive_emptyInbox_returnsNull() {
        SimpleTransport transport = new SimpleTransport();

        WorkRequest received = transport.receive(Duration.ofMillis(50));
        assertThat(received).isNull();
    }

    // ========================
    // Deliver
    // ========================

    @Test
    void deliver_storesResponse() {
        SimpleTransport transport = new SimpleTransport();
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 500L);

        transport.deliver(response);

        WorkResponse retrieved = transport.resultStore().retrieve("req-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-1");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("done");
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void send_null_throwsNPE() {
        SimpleTransport transport = new SimpleTransport();
        assertThatThrownBy(() -> transport.send(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void receive_nullTimeout_throwsNPE() {
        SimpleTransport transport = new SimpleTransport();
        assertThatThrownBy(() -> transport.receive(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliver_null_throwsNPE() {
        SimpleTransport transport = new SimpleTransport();
        assertThatThrownBy(() -> transport.deliver(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Lifecycle
    // ========================

    @Test
    void close_isIdempotent() {
        SimpleTransport transport = new SimpleTransport();
        assertThatNoException().isThrownBy(() -> {
            transport.close();
            transport.close();
        });
    }

    // ========================
    // Accessors
    // ========================

    @Test
    void requestQueue_returnsInstance() {
        SimpleTransport transport = new SimpleTransport();
        assertThat(transport.requestQueue()).isNotNull();
    }

    @Test
    void resultStore_returnsInstance() {
        SimpleTransport transport = new SimpleTransport();
        assertThat(transport.resultStore()).isNotNull();
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_sendAndDeliver_threadSafe() throws Exception {
        SimpleTransport transport = new SimpleTransport();
        int count = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> deliveredIds = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Senders
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    transport.send(workRequest("req-" + idx, "task"));
                });
            }

            // Deliverers
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    transport.deliver(new WorkResponse("req-" + idx, "COMPLETED", "r" + idx, null, 10L));
                    deliveredIds.add("req-" + idx);
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(deliveredIds).hasSize(count);

        // Verify all responses are retrievable
        for (int i = 0; i < count; i++) {
            WorkResponse resp = transport.resultStore().retrieve("req-" + i);
            assertThat(resp).isNotNull();
            assertThat(resp.status()).isEqualTo("COMPLETED");
        }
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
