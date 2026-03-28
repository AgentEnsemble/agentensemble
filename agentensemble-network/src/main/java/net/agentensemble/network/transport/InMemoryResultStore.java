package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * In-memory {@link ResultStore} backed by {@link ConcurrentHashMap}.
 *
 * <p>Stores work responses keyed by request ID. TTL values are accepted but not enforced
 * (entries remain until the process exits). Subscribers are notified when a result is
 * stored for a watched request ID.
 *
 * <p>Suitable for development and testing. Does not survive process restarts.
 *
 * <p>Thread-safe: storage uses {@link ConcurrentHashMap}, callbacks use
 * {@link CopyOnWriteArrayList}.
 */
class InMemoryResultStore implements ResultStore {

    private final ConcurrentHashMap<String, WorkResponse> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Consumer<WorkResponse>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void store(String requestId, WorkResponse response, Duration ttl) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        store.put(requestId, response);

        List<Consumer<WorkResponse>> callbacks = subscribers.get(requestId);
        if (callbacks != null) {
            for (Consumer<WorkResponse> callback : callbacks) {
                callback.accept(response);
            }
        }
    }

    @Override
    public WorkResponse retrieve(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        return store.get(requestId);
    }

    @Override
    public void subscribe(String requestId, Consumer<WorkResponse> callback) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");
        subscribers
                .computeIfAbsent(requestId, k -> new CopyOnWriteArrayList<>())
                .add(callback);

        // Check for already-stored result to prevent lost notifications from race conditions.
        WorkResponse existing = store.get(requestId);
        if (existing != null) {
            callback.accept(existing);
        }
    }
}
