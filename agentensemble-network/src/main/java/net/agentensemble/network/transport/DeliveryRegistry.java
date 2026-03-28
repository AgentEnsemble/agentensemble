package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.network.transport.delivery.NoneDeliveryHandler;
import net.agentensemble.network.transport.delivery.StoreDeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Registry of {@link DeliveryHandler} instances keyed by {@link DeliveryMethod}.
 *
 * <p>Provides O(1) lookup of delivery handlers and dispatches work responses to the
 * appropriate handler based on the delivery method specified in the {@link DeliverySpec}.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod
 */
public final class DeliveryRegistry {

    private final ConcurrentHashMap<DeliveryMethod, DeliveryHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Register a delivery handler.
     *
     * <p>The handler is registered under the {@link DeliveryMethod} returned by
     * {@link DeliveryHandler#method()}.
     *
     * @param handler the handler to register; must not be null
     * @throws NullPointerException  if {@code handler} is null
     * @throws IllegalStateException if a handler is already registered for the method
     */
    public void register(DeliveryHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        DeliveryMethod method = handler.method();
        DeliveryHandler existing = handlers.putIfAbsent(method, handler);
        if (existing != null) {
            throw new IllegalStateException("Handler already registered for delivery method: " + method);
        }
    }

    /**
     * Deliver a work response using the handler registered for the spec's delivery method.
     *
     * @param spec     the delivery specification; must not be null
     * @param response the work response to deliver; must not be null
     * @throws IllegalStateException if no handler is registered for the spec's method
     */
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        DeliveryHandler handler = handlers.get(spec.method());
        if (handler == null) {
            throw new IllegalStateException("No delivery handler registered for method: " + spec.method());
        }
        handler.deliver(spec, response);
    }

    /**
     * Check whether a handler is registered for the given delivery method.
     *
     * @param method the delivery method to check; must not be null
     * @return {@code true} if a handler is registered
     */
    public boolean hasHandler(DeliveryMethod method) {
        Objects.requireNonNull(method, "method must not be null");
        return handlers.containsKey(method);
    }

    /**
     * Create a registry pre-registered with the default handlers: {@link StoreDeliveryHandler}
     * and {@link NoneDeliveryHandler}.
     *
     * @param resultStore the result store for the {@link StoreDeliveryHandler}; must not be null
     * @return a new registry with default handlers registered
     */
    public static DeliveryRegistry withDefaults(ResultStore resultStore) {
        Objects.requireNonNull(resultStore, "resultStore must not be null");
        DeliveryRegistry registry = new DeliveryRegistry();
        registry.register(new StoreDeliveryHandler(resultStore, Duration.ofHours(1)));
        registry.register(new NoneDeliveryHandler());
        return registry;
    }
}
