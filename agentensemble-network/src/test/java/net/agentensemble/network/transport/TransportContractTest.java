package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link Transport} SPI.
 *
 * <p>Verifies the behaviour that any {@code Transport} implementation must satisfy. Currently
 * tests against {@link SimpleTransport} (the only implementation). When additional
 * implementations are added, this can be refactored into a parameterized or abstract base test.
 */
class TransportContractTest {

    // ========================
    // Factory
    // ========================

    @Test
    void websocket_factoryReturnsNonNull() {
        Transport transport = Transport.websocket();
        assertThat(transport).isNotNull();
    }

    @Test
    void websocket_factoryReturnsSimpleTransport() {
        Transport transport = Transport.websocket();
        assertThat(transport).isInstanceOf(SimpleTransport.class);
    }

    // ========================
    // Send / receive round-trip
    // ========================

    @Test
    void send_then_receive_fromTaskQueue() {
        SimpleTransport transport = new SimpleTransport();
        WorkRequest request = workRequest("req-1", "prepare-meal");

        transport.send(request);

        // SimpleTransport.send enqueues under the task name; verify via requestQueue
        WorkRequest received = transport.requestQueue().dequeue("prepare-meal", Duration.ofSeconds(1));
        assertThat(received).isNotNull();
        assertThat(received.requestId()).isEqualTo("req-1");
        assertThat(received.task()).isEqualTo("prepare-meal");
    }

    // ========================
    // Receive timeout
    // ========================

    @Test
    void receive_timeout_returnsNull() {
        Transport transport = Transport.websocket();

        WorkRequest result = transport.receive(Duration.ofMillis(50));
        assertThat(result).isNull();
    }

    // ========================
    // Deliver stores response
    // ========================

    @Test
    void deliver_storesResponse_retrievableViaResultStore() {
        SimpleTransport transport = new SimpleTransport();
        WorkResponse response = new WorkResponse("req-42", "COMPLETED", "output", null, 1000L);

        transport.deliver(response);

        WorkResponse retrieved = transport.resultStore().retrieve("req-42");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-42");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("output");
    }

    @Test
    void deliver_triggersSubscription() {
        SimpleTransport transport = new SimpleTransport();
        AtomicReference<WorkResponse> captured = new AtomicReference<>();

        transport.resultStore().subscribe("req-99", captured::set);
        transport.deliver(new WorkResponse("req-99", "FAILED", null, "timeout", 5000L));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().requestId()).isEqualTo("req-99");
        assertThat(captured.get().error()).isEqualTo("timeout");
    }

    // ========================
    // Close
    // ========================

    @Test
    void close_defaultIsNoOp() {
        Transport transport = Transport.websocket();
        transport.close(); // should not throw
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null);
    }
}
