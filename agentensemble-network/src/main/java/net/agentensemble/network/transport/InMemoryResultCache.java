package net.agentensemble.network.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * In-memory {@link ResultCache} backed by {@link ConcurrentHashMap}.
 *
 * <p>Cached entries are lazily evicted when accessed after their TTL has expired.
 * Suitable for development and testing. Does not survive process restarts.
 *
 * <p>Thread-safe: all operations use {@link ConcurrentHashMap}.
 */
class InMemoryResultCache implements ResultCache {

    private record CacheEntry(WorkResponse response, Instant expiresAt) {}

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public void cache(String cacheKey, WorkResponse response, Duration maxAge) {
        Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        cache.put(cacheKey, new CacheEntry(response, Instant.now().plus(maxAge)));
    }

    @Override
    public WorkResponse get(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        CacheEntry entry = cache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            cache.remove(cacheKey); // lazy eviction
            return null;
        }
        return entry.response();
    }
}
