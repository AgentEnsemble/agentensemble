package net.agentensemble.network.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * In-memory {@link IdempotencyGuard} backed by {@link ConcurrentHashMap}.
 *
 * <p>Tracks request IDs to ensure at-most-once execution. Entries are lazily evicted
 * when accessed after their TTL has expired. Suitable for development and testing.
 * Does not survive process restarts.
 *
 * <p>Thread-safe: all operations use {@link ConcurrentHashMap}.
 */
class InMemoryIdempotencyGuard implements IdempotencyGuard {

    private enum State {
        IN_PROGRESS,
        COMPLETED
    }

    private record GuardEntry(State state, WorkResponse response, Instant expiresAt) {}

    private final ConcurrentHashMap<String, GuardEntry> entries = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        GuardEntry existing = entries.get(requestId);
        if (existing != null) {
            // Check if expired
            if (Instant.now().isAfter(existing.expiresAt())) {
                entries.remove(requestId);
                // Fall through to acquire
            } else {
                return false; // duplicate
            }
        }
        // Atomic put-if-absent
        GuardEntry newEntry =
                new GuardEntry(State.IN_PROGRESS, null, Instant.now().plus(Duration.ofHours(1)));
        return entries.putIfAbsent(requestId, newEntry) == null;
    }

    @Override
    public void release(String requestId, WorkResponse response, Duration ttl) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        entries.put(
                requestId,
                new GuardEntry(State.COMPLETED, response, Instant.now().plus(ttl)));
    }

    @Override
    public WorkResponse getExistingResult(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        GuardEntry entry = entries.get(requestId);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(requestId);
            return null;
        }
        return entry.response(); // null if IN_PROGRESS
    }
}
