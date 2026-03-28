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
 * Delivery handler that sends responses over WebSocket as JSON.
 *
 * <p>The actual WebSocket send is delegated to a {@link Consumer} so that the handler
 * remains decoupled from any specific WebSocket implementation (e.g., Jakarta WebSocket,
 * Spring WebSocket).
 *
 * <p>Thread-safety depends on the provided {@code sender}.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#WEBSOCKET
 */
public final class WebSocketDeliveryHandler implements DeliveryHandler {

    private final Consumer<String> sender;
    private final ObjectMapper mapper;

    /**
     * Create a WebSocket delivery handler.
     *
     * @param sender function that sends a JSON string over WebSocket; must not be null
     */
    public WebSocketDeliveryHandler(Consumer<String> sender) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Create a WebSocket delivery handler with a custom ObjectMapper.
     *
     * @param sender function that sends a JSON string over WebSocket; must not be null
     * @param mapper the Jackson ObjectMapper to use for serialization; must not be null
     */
    public WebSocketDeliveryHandler(Consumer<String> sender, ObjectMapper mapper) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.WEBSOCKET;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        try {
            String json = mapper.writeValueAsString(response);
            sender.accept(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize WorkResponse to JSON for request " + response.requestId(), e);
        }
    }
}
