package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeliveryRegistry}.
 */
class DeliveryRegistryTest {

    @Test
    void register_validHandler_succeeds() {
        DeliveryRegistry registry = new DeliveryRegistry();
        DeliveryHandler handler = stubHandler(DeliveryMethod.STORE);

        registry.register(handler);

        assertThat(registry.hasHandler(DeliveryMethod.STORE)).isTrue();
    }

    @Test
    void register_null_throwsNPE() {
        DeliveryRegistry registry = new DeliveryRegistry();

        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void register_duplicate_throwsISE() {
        DeliveryRegistry registry = new DeliveryRegistry();
        registry.register(stubHandler(DeliveryMethod.STORE));

        assertThatThrownBy(() -> registry.register(stubHandler(DeliveryMethod.STORE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STORE");
    }

    @Test
    void deliver_registeredMethod_delegatesToHandler() {
        DeliveryRegistry registry = new DeliveryRegistry();
        DeliveryHandler handler = mock(DeliveryHandler.class);
        org.mockito.Mockito.when(handler.method()).thenReturn(DeliveryMethod.QUEUE);
        registry.register(handler);

        DeliverySpec spec = new DeliverySpec(DeliveryMethod.QUEUE, "results");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        registry.deliver(spec, response);

        verify(handler).deliver(spec, response);
    }

    @Test
    void deliver_unregisteredMethod_throwsISE() {
        DeliveryRegistry registry = new DeliveryRegistry();
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.WEBHOOK, "https://example.com");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> registry.deliver(spec, response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBHOOK");
    }

    @Test
    void hasHandler_registered_returnsTrue() {
        DeliveryRegistry registry = new DeliveryRegistry();
        registry.register(stubHandler(DeliveryMethod.NONE));

        assertThat(registry.hasHandler(DeliveryMethod.NONE)).isTrue();
    }

    @Test
    void hasHandler_unregistered_returnsFalse() {
        DeliveryRegistry registry = new DeliveryRegistry();

        assertThat(registry.hasHandler(DeliveryMethod.WEBHOOK)).isFalse();
    }

    @Test
    void withDefaults_registersStoreAndNone() {
        ResultStore resultStore = ResultStore.inMemory();
        DeliveryRegistry registry = DeliveryRegistry.withDefaults(resultStore);

        assertThat(registry.hasHandler(DeliveryMethod.STORE)).isTrue();
        assertThat(registry.hasHandler(DeliveryMethod.NONE)).isTrue();
        assertThat(registry.hasHandler(DeliveryMethod.QUEUE)).isFalse();
        assertThat(registry.hasHandler(DeliveryMethod.WEBHOOK)).isFalse();
    }

    private static DeliveryHandler stubHandler(DeliveryMethod method) {
        return new DeliveryHandler() {
            @Override
            public DeliveryMethod method() {
                return method;
            }

            @Override
            public void deliver(DeliverySpec spec, WorkResponse response) {
                // no-op stub
            }
        };
    }
}
