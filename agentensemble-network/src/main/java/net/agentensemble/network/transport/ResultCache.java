package net.agentensemble.network.transport;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for result caching based on semantic cache keys.
 *
 * <p>Unlike {@link ResultStore} (keyed by request ID for response routing), a
 * {@code ResultCache} is keyed by application-defined cache keys. This supports
 * the pattern where semantically identical requests can share cached results.
 *
 * <p>Implementations must be thread-safe.
 *
 * @see net.agentensemble.web.protocol.CachePolicy
 * @see net.agentensemble.web.protocol.WorkRequest#cacheKey()
 */
public interface ResultCache {
    /**
     * Cache a response under the given key with a maximum age.
     *
     * @param cacheKey the cache key; must not be null
     * @param response the response to cache; must not be null
     * @param maxAge   how long the entry remains valid; must not be null
     */
    void cache(String cacheKey, WorkResponse response, Duration maxAge);

    /**
     * Retrieve a cached response by key.
     *
     * @param cacheKey the cache key; must not be null
     * @return the cached response, or {@code null} if not found or expired
     */
    WorkResponse get(String cacheKey);

    /**
     * Create an in-memory result cache with lazy TTL enforcement.
     *
     * <p>Suitable for development and testing. Does not survive process restarts.
     *
     * @return a new in-memory result cache
     */
    static ResultCache inMemory() {
        return new InMemoryResultCache();
    }
}
