package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for shared work response storage.
 *
 * <p>A {@code ResultStore} provides a key-value store for work responses, keyed by request ID.
 * This supports the asymmetric routing pattern where the pod that processes a request may
 * differ from the pod that received it -- the result is written to a shared store and
 * retrieved by any replica.
 *
 * <p>Use the built-in factory for development:
 * <ul>
 *   <li>{@link #inMemory()} -- in-process store, single-JVM only</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 *
 * @see Transport
 * @see WorkResponse
 */
public interface ResultStore {

    /**
     * Store a work response keyed by request ID, with a TTL for automatic cleanup.
     *
     * @param requestId the request ID key; must not be null
     * @param response  the work response to store; must not be null
     * @param ttl       time-to-live for the entry; must not be null
     */
    void store(String requestId, WorkResponse response, Duration ttl);

    /**
     * Retrieve a stored response by request ID.
     *
     * @param requestId the request ID to look up; must not be null
     * @return the stored response, or {@code null} if not found or expired
     */
    WorkResponse retrieve(String requestId);

    /**
     * Subscribe for notification when a result for the given request ID is stored.
     *
     * <p>The callback is invoked when {@link #store} is called with a matching request ID.
     * If the result was already stored before subscribing, the callback is not invoked.
     *
     * @param requestId the request ID to watch; must not be null
     * @param callback  the callback to invoke; must not be null
     */
    void subscribe(String requestId, Consumer<WorkResponse> callback);

    /**
     * Create an in-memory result store backed by {@link java.util.concurrent.ConcurrentHashMap}.
     *
     * <p>Suitable for development and testing. TTL is accepted but not enforced. Does not
     * survive process restarts.
     *
     * @return a new {@link InMemoryResultStore}
     */
    static ResultStore inMemory() {
        return new InMemoryResultStore();
    }
}
