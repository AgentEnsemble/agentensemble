package net.agentensemble.network.transport.delivery;

import java.time.Duration;
import java.util.Objects;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.network.transport.ResultStore;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Delivery handler that writes responses to a shared {@link ResultStore}.
 *
 * <p>The requester retrieves the result by polling or subscribing to the store using the
 * request ID as the key.
 *
 * <p>Thread-safe: delegates to a thread-safe {@link ResultStore}.
 *
 * @see DeliveryHandler
 * @see ResultStore
 */
public final class StoreDeliveryHandler implements DeliveryHandler {

    private final ResultStore resultStore;
    private final Duration ttl;

    /**
     * Create a store delivery handler.
     *
     * @param resultStore the result store to write responses to; must not be null
     * @param ttl         the time-to-live for stored responses; must not be null
     */
    public StoreDeliveryHandler(ResultStore resultStore, Duration ttl) {
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.STORE;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        resultStore.store(response.requestId(), response, ttl);
    }
}
