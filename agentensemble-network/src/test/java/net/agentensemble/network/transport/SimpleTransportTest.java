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
    // Send / receive round-trip
    // ========================

    @Test
    void send_then_receive_roundTrip() {
        SimpleTransport transport = new SimpleTransport("kitchen");

        WorkRequest request = workRequest("req-1", "prepare-meal");
        transport.send(request);

        WorkRequest received = transport.receive(Duration.ofSeconds(1));
        assertThat(received).isNotNull();
        assertThat(received.requestId()).isEqualTo("req-1");
        assertThat(received.task()).isEqualTo("prepare-meal");
    }

    @Test
    void receive_emptyInbox_returnsNull() {
        SimpleTransport transport = new SimpleTransport("kitchen");

        WorkRequest received = transport.receive(Duration.ofMillis(50));
        assertThat(received).isNull();
    }

    @Test
    void send_multipleRequests_receivedInFifoOrder() {
        SimpleTransport transport = new SimpleTransport("kitchen");

        transport.send(workRequest("first", "task"));
        transport.send(workRequest("second", "task"));
        transport.send(workRequest("third", "task"));

        assertThat(transport.receive(Duration.ofSeconds(1)).requestId()).isEqualTo("first");
        assertThat(transport.receive(Duration.ofSeconds(1)).requestId()).isEqualTo("second");
        assertThat(transport.receive(Duration.ofSeconds(1)).requestId()).isEqualTo("third");
    }

    // ========================
    // Deliver
    // ========================

    @Test
    void deliver_storesResponse() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 500L);

        transport.deliver(response);

        WorkResponse retrieved = transport.resultStore().retrieve("req-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-1");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("done");
    }

    // ========================
    // Ensemble name
    // ========================

    @Test
    void ensembleName_returnsConfiguredName() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        assertThat(transport.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void differentEnsembleNames_haveIndependentInboxes() {
        SimpleTransport kitchen = new SimpleTransport("kitchen");
        SimpleTransport maintenance = new SimpleTransport("maintenance");

        kitchen.send(workRequest("req-k", "cook"));
        maintenance.send(workRequest("req-m", "repair"));

        // Each transport only receives from its own inbox
        assertThat(kitchen.receive(Duration.ofMillis(50)).requestId()).isEqualTo("req-k");
        assertThat(maintenance.receive(Duration.ofMillis(50)).requestId()).isEqualTo("req-m");
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void constructor_nullEnsembleName_throwsNPE() {
        assertThatThrownBy(() -> new SimpleTransport(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void send_null_throwsNPE() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        assertThatThrownBy(() -> transport.send(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void receive_nullTimeout_throwsNPE() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        assertThatThrownBy(() -> transport.receive(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliver_null_throwsNPE() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        assertThatThrownBy(() -> transport.deliver(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Lifecycle
    // ========================

    @Test
    void close_isIdempotent() {
        SimpleTransport transport = new SimpleTransport("kitchen");
        assertThatNoException().isThrownBy(() -> {
            transport.close();
            transport.close();
        });
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrent_sendAndReceive_threadSafe() throws Exception {
        SimpleTransport transport = new SimpleTransport("kitchen");
        int count = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<String> receivedIds = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            // Senders
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    transport.send(workRequest("req-" + idx, "task"));
                });
            }

            // Receivers
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    WorkRequest req = transport.receive(Duration.ofSeconds(5));
                    if (req != null) {
                        receivedIds.add(req.requestId());
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(receivedIds).hasSize(count);
    }

    @Test
    void concurrent_deliver_threadSafe() throws Exception {
        SimpleTransport transport = new SimpleTransport("kitchen");
        int count = 50;
        CountDownLatch startLatch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < count; i++) {
                int idx = i;
                executor.submit(() -> {
                    awaitQuietly(startLatch);
                    transport.deliver(new WorkResponse("req-" + idx, "COMPLETED", "r" + idx, null, 10L));
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

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
