package net.agentensemble.network.transport.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoneDeliveryHandler}.
 */
class NoneDeliveryHandlerTest {

    @Test
    void method_returnsNone() {
        NoneDeliveryHandler handler = new NoneDeliveryHandler();

        assertThat(handler.method()).isEqualTo(DeliveryMethod.NONE);
    }

    @Test
    void deliver_doesNotThrow() {
        NoneDeliveryHandler handler = new NoneDeliveryHandler();
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.NONE, null);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatNoException().isThrownBy(() -> handler.deliver(spec, response));
    }
}
