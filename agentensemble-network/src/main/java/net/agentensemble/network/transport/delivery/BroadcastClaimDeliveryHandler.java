package net.agentensemble.network.transport.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Delivery handler that broadcasts responses to all replicas as JSON.
 *
 * <p>The first replica to claim the broadcast receives the payload. The actual broadcast
 * mechanism is delegated to a {@link Consumer} so that any pub/sub implementation can be
 * plugged in.
 *
 * <p>Thread-safety depends on the provided {@code broadcaster}.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#BROADCAST_CLAIM
 */
public final class BroadcastClaimDeliveryHandler implements DeliveryHandler {

    private final Consumer<String> broadcaster;
    private final ObjectMapper mapper;

    /**
     * Create a broadcast-claim delivery handler.
     *
     * @param broadcaster function that broadcasts a JSON string to all replicas; must not be null
     */
    public BroadcastClaimDeliveryHandler(Consumer<String> broadcaster) {
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Create a broadcast-claim delivery handler with a custom ObjectMapper.
     *
     * @param broadcaster function that broadcasts a JSON string to all replicas; must not be null
     * @param mapper      the Jackson ObjectMapper to use for serialization; must not be null
     */
    public BroadcastClaimDeliveryHandler(Consumer<String> broadcaster, ObjectMapper mapper) {
        this.broadcaster = Objects.requireNonNull(broadcaster, "broadcaster must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.BROADCAST_CLAIM;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        try {
            String json = mapper.writeValueAsString(response);
            broadcaster.accept(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize WorkResponse to JSON for request " + response.requestId(), e);
        }
    }
}
