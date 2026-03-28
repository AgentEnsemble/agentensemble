package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeliverySpec}.
 */
class DeliverySpecTest {

    @Test
    void constructionWithAllFields() {
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.WEBHOOK, "https://example.com/callback");
        assertThat(spec.method()).isEqualTo(DeliveryMethod.WEBHOOK);
        assertThat(spec.address()).isEqualTo("https://example.com/callback");
    }

    @Test
    void constructionWithNullAddress() {
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.WEBSOCKET, null);
        assertThat(spec.method()).isEqualTo(DeliveryMethod.WEBSOCKET);
        assertThat(spec.address()).isNull();
    }

    @Test
    void nullMethodThrows() {
        assertThatThrownBy(() -> new DeliverySpec(null, "some-address")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        DeliverySpec original = new DeliverySpec(DeliveryMethod.QUEUE, "my-queue");
        String json = mapper.writeValueAsString(original);

        assertThat(json).contains("\"method\":\"QUEUE\"");
        assertThat(json).contains("\"address\":\"my-queue\"");

        DeliverySpec deserialized = mapper.readValue(json, DeliverySpec.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void equality() {
        DeliverySpec a = new DeliverySpec(DeliveryMethod.TOPIC, "events");
        DeliverySpec b = new DeliverySpec(DeliveryMethod.TOPIC, "events");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
