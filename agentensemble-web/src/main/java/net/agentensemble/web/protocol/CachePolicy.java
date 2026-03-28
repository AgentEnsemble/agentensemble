package net.agentensemble.web.protocol;

/**
 * Caching policy for cross-ensemble work requests.
 *
 * @see WorkRequest
 */
public enum CachePolicy {

    /** Use a cached result if one exists and is still valid. */
    USE_CACHED,

    /** Bypass the cache and force fresh execution. */
    FORCE_FRESH
}
