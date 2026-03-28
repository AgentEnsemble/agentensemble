package net.agentensemble.network.transport;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for preventing duplicate request processing.
 *
 * <p>Each request ID is tracked to ensure at-most-once execution. The guard
 * maintains three states for a request ID:
 * <ul>
 *   <li>Unknown -- not seen before; {@link #tryAcquire} returns {@code true}</li>
 *   <li>In-progress -- acquired but not released; {@link #tryAcquire} returns {@code false}</li>
 *   <li>Completed -- released with a response; {@link #getExistingResult} returns the response</li>
 * </ul>
 *
 * <p>Entries expire after a configurable TTL to prevent unbounded growth.
 *
 * <p>Implementations must be thread-safe.
 */
public interface IdempotencyGuard {
    /**
     * Attempt to acquire the right to process the given request ID.
     *
     * @param requestId the request ID; must not be null
     * @return {@code true} if this is the first time (proceed with execution),
     *         {@code false} if already in-progress or completed (duplicate)
     */
    boolean tryAcquire(String requestId);

    /**
     * Release a request ID after processing, storing the final response.
     *
     * @param requestId the request ID; must not be null
     * @param response  the final response; must not be null
     * @param ttl       how long to remember the response; must not be null
     */
    void release(String requestId, WorkResponse response, Duration ttl);

    /**
     * Get the existing result for a previously-completed request.
     *
     * @param requestId the request ID; must not be null
     * @return the response if completed and not expired, or {@code null}
     */
    WorkResponse getExistingResult(String requestId);

    /**
     * Create an in-memory idempotency guard with lazy TTL enforcement.
     *
     * @return a new in-memory guard
     */
    static IdempotencyGuard inMemory() {
        return new InMemoryIdempotencyGuard();
    }
}
