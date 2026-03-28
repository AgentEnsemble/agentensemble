package net.agentensemble.network.transport.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import net.agentensemble.network.transport.ResultStore;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StoreDeliveryHandler}.
 */
class StoreDeliveryHandlerTest {

    private static final Duration TTL = Duration.ofHours(1);

    @Test
    void method_returnsStore() {
        ResultStore store = mock(ResultStore.class);
        StoreDeliveryHandler handler = new StoreDeliveryHandler(store, TTL);

        assertThat(handler.method()).isEqualTo(DeliveryMethod.STORE);
    }

    @Test
    void deliver_delegatesToResultStore() {
        ResultStore store = mock(ResultStore.class);
        StoreDeliveryHandler handler = new StoreDeliveryHandler(store, TTL);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.STORE, null);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        handler.deliver(spec, response);

        verify(store).store("req-1", response, TTL);
    }

    @Test
    void deliver_nullSpec_throwsNPE() {
        ResultStore store = mock(ResultStore.class);
        StoreDeliveryHandler handler = new StoreDeliveryHandler(store, TTL);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> handler.deliver(null, response)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deliver_nullResponse_throwsNPE() {
        ResultStore store = mock(ResultStore.class);
        StoreDeliveryHandler handler = new StoreDeliveryHandler(store, TTL);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.STORE, null);

        assertThatThrownBy(() -> handler.deliver(spec, null)).isInstanceOf(NullPointerException.class);
    }
}
