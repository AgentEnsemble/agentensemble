package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.network.transport.delivery.QueueDeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for delivery routing through {@link SimpleTransport} with
 * {@link DeliveryRegistry}.
 */
class DeliveryRoutingIntegrationTest {

    @Test
    void routeToQueue_delivery() {
        AtomicReference<String> capturedQueue = new AtomicReference<>();
        AtomicReference<WorkResponse> capturedResponse = new AtomicReference<>();

        DeliveryRegistry registry = new DeliveryRegistry();
        registry.register(new QueueDeliveryHandler((q, r) -> {
            capturedQueue.set(q);
            capturedResponse.set(r);
        }));

        try (Transport transport = Transport.simple("kitchen", registry)) {
            WorkRequest request = new WorkRequest(
                    "req-1",
                    "test",
                    "task",
                    null,
                    null,
                    null,
                    new DeliverySpec(DeliveryMethod.QUEUE, "results"),
                    null,
                    null,
                    null,
                    null);
            transport.send(request);

            WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);
            transport.deliver(response);

            assertThat(capturedQueue.get()).isEqualTo("results");
            assertThat(capturedResponse.get()).isSameAs(response);
        }
    }

    @Test
    void routeToStore_delivery() {
        ResultStore resultStore = ResultStore.inMemory();
        DeliveryRegistry registry = DeliveryRegistry.withDefaults(resultStore);

        SimpleTransport transport = new SimpleTransport("kitchen", RequestQueue.inMemory(), resultStore, registry);

        WorkRequest request = new WorkRequest(
                "req-2",
                "test",
                "task",
                null,
                null,
                null,
                new DeliverySpec(DeliveryMethod.STORE, null),
                null,
                null,
                null,
                null);
        transport.send(request);

        WorkResponse response = new WorkResponse("req-2", "COMPLETED", "stored", null, 200L);
        transport.deliver(response);

        WorkResponse retrieved = resultStore.retrieve("req-2");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-2");
        assertThat(retrieved.result()).isEqualTo("stored");
    }

    @Test
    void fallback_noRegistry_usesResultStore() {
        SimpleTransport transport = new SimpleTransport("kitchen");

        WorkRequest request = new WorkRequest(
                "req-3",
                "test",
                "task",
                null,
                null,
                null,
                new DeliverySpec(DeliveryMethod.QUEUE, "results"),
                null,
                null,
                null,
                null);
        transport.send(request);

        WorkResponse response = new WorkResponse("req-3", "COMPLETED", "fallback", null, 300L);
        transport.deliver(response);

        // Without a registry, should fall back to result store
        WorkResponse retrieved = transport.resultStore().retrieve("req-3");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-3");
        assertThat(retrieved.result()).isEqualTo("fallback");
    }
}
