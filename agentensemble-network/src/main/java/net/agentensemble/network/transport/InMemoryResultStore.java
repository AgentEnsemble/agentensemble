package net.agentensemble.network.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * In-memory {@link ResultStore} backed by {@link ConcurrentHashMap}.
 *
 * <p>Stores work responses keyed by request ID with lazy TTL expiration: expired entries
 * are removed on {@link #retrieve} calls. Note that entries which are stored but never
 * retrieved will not be evicted — this is acceptable for the development/testing use
 * case this implementation targets.
 *
 * <p>Suitable for development and testing. Does not survive process restarts.
 *
 * <p>Thread-safe: storage uses {@link ConcurrentHashMap}. Subscriber callbacks are
 * guaranteed at-most-once delivery via atomic {@link ConcurrentHashMap#remove} — the
 * same pattern used by {@code RedisResultStore}.
 */
class InMemoryResultStore implements ResultStore {

    private record TimedEntry(WorkResponse response, Instant expiresAt) {}

    private final ConcurrentHashMap<String, TimedEntry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<WorkResponse>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void store(String requestId, WorkResponse response, Duration ttl) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        store.put(requestId, new TimedEntry(response, Instant.now().plus(ttl)));

        // Atomically remove the subscriber: if remove() returns non-null, we are the
        // one responsible for invoking the callback (at-most-once guarantee).
        Consumer<WorkResponse> callback = subscribers.remove(requestId);
        if (callback != null) {
            callback.accept(response);
        }
    }

    @Override
    public WorkResponse retrieve(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        TimedEntry entry = store.get(requestId);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            // Remove only the specific entry we observed to avoid deleting a newer value
            store.remove(requestId, entry);
            return null;
        }
        return entry.response();
    }

    @Override
    public void subscribe(String requestId, Consumer<WorkResponse> callback) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        // Register callback before checking for an existing result.
        subscribers.put(requestId, callback);

        // Check for already-stored result to prevent lost notifications from race
        // conditions. Atomically remove the subscriber: if remove() returns non-null,
        // we are responsible for invoking the callback; if null, store() already did.
        WorkResponse existing = retrieve(requestId);
        if (existing != null) {
            Consumer<WorkResponse> removed = subscribers.remove(requestId);
            if (removed != null) {
                removed.accept(existing);
            }
        }
    }
}
