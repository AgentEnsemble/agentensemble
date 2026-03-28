package net.agentensemble.network.transport.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueDeliveryHandler}.
 */
class QueueDeliveryHandlerTest {

    @Test
    void method_returnsQueue() {
        QueueDeliveryHandler handler = new QueueDeliveryHandler((q, r) -> {});

        assertThat(handler.method()).isEqualTo(DeliveryMethod.QUEUE);
    }

    @Test
    void deliver_callsQueueWriter() {
        AtomicReference<String> capturedQueue = new AtomicReference<>();
        AtomicReference<WorkResponse> capturedResponse = new AtomicReference<>();
        QueueDeliveryHandler handler = new QueueDeliveryHandler((q, r) -> {
            capturedQueue.set(q);
            capturedResponse.set(r);
        });
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.QUEUE, "results");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        handler.deliver(spec, response);

        assertThat(capturedQueue.get()).isEqualTo("results");
        assertThat(capturedResponse.get()).isSameAs(response);
    }

    @Test
    void deliver_nullAddress_throwsIAE() {
        QueueDeliveryHandler handler = new QueueDeliveryHandler((q, r) -> {});
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.QUEUE, null);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> handler.deliver(spec, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue name");
    }

    @Test
    void deliver_blankAddress_throwsIAE() {
        QueueDeliveryHandler handler = new QueueDeliveryHandler((q, r) -> {});
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.QUEUE, "   ");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> handler.deliver(spec, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue name");
    }
}
