package net.agentensemble.dashboard;

import java.time.Duration;

/**
 * Caching and idempotency metadata passed alongside a work request.
 *
 * <p>This record bridges the gap between the wire-protocol layer (which has the full
 * {@code WorkRequest}) and the core request handler (which does not depend on the
 * web module).
 */
public record RequestContext(String requestId, String cacheKey, String cachePolicy, Duration maxAge) {}
