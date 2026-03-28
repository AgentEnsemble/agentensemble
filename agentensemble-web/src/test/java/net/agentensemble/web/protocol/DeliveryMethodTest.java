package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeliveryMethod}.
 */
class DeliveryMethodTest {

    @Test
    void allValuesExist() {
        assertThat(DeliveryMethod.values())
                .containsExactly(
                        DeliveryMethod.WEBSOCKET,
                        DeliveryMethod.QUEUE,
                        DeliveryMethod.TOPIC,
                        DeliveryMethod.WEBHOOK,
                        DeliveryMethod.STORE,
                        DeliveryMethod.BROADCAST_CLAIM,
                        DeliveryMethod.NONE);
    }

    @Test
    void valueOfRoundTrip() {
        for (DeliveryMethod m : DeliveryMethod.values()) {
            assertThat(DeliveryMethod.valueOf(m.name())).isEqualTo(m);
        }
    }
}
