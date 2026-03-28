package net.agentensemble.network.transport.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BroadcastClaimDeliveryHandler}.
 */
class BroadcastClaimDeliveryHandlerTest {

    @Test
    void method_returnsBroadcastClaim() {
        BroadcastClaimDeliveryHandler handler = new BroadcastClaimDeliveryHandler(s -> {});

        assertThat(handler.method()).isEqualTo(DeliveryMethod.BROADCAST_CLAIM);
    }

    @Test
    void deliver_broadcastsJson() throws Exception {
        AtomicReference<String> capturedJson = new AtomicReference<>();
        BroadcastClaimDeliveryHandler handler = new BroadcastClaimDeliveryHandler(capturedJson::set);
        DeliverySpec spec = new DeliverySpec(DeliveryMethod.BROADCAST_CLAIM, null);
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        handler.deliver(spec, response);

        String json = capturedJson.get();
        assertThat(json).isNotNull();

        // Verify the JSON can be deserialized back
        ObjectMapper mapper = new ObjectMapper();
        WorkResponse deserialized = mapper.readValue(json, WorkResponse.class);
        assertThat(deserialized.requestId()).isEqualTo("req-1");
        assertThat(deserialized.status()).isEqualTo("COMPLETED");
        assertThat(deserialized.result()).isEqualTo("done");
    }
}
