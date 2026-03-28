package net.agentensemble.network.transport;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkRequest;

/**
 * SPI for durable work request queues.
 *
 * <p>A {@code RequestQueue} delivers work requests between ensembles. Implementations must
 * support named queues so that each ensemble has its own inbox. Production implementations
 * should support consumer groups for horizontal scaling (multiple replicas reading from the
 * same queue with at-least-once delivery).
 *
 * <p>Use the built-in factory for development:
 * <ul>
 *   <li>{@link #inMemory()} -- in-process queues, single-JVM only</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 *
 * @see Transport
 * @see WorkRequest
 */
public interface RequestQueue {

    /**
     * Enqueue a work request for a target ensemble.
     *
     * @param queueName the name of the target queue (typically the ensemble name); must not be null
     * @param request   the work request to enqueue; must not be null
     */
    void enqueue(String queueName, WorkRequest request);

    /**
     * Dequeue the next work request, blocking up to the given timeout.
     *
     * @param queueName the name of the queue to read from; must not be null
     * @param timeout   maximum time to wait; must not be null
     * @return the next work request, or {@code null} on timeout
     */
    WorkRequest dequeue(String queueName, Duration timeout);

    /**
     * Acknowledge successful processing of a request.
     *
     * <p>Removes the message from the queue so it is not redelivered. For in-memory
     * implementations, this is a no-op since messages are removed on dequeue.
     *
     * @param queueName the name of the queue; must not be null
     * @param requestId the request ID to acknowledge; must not be null
     */
    void acknowledge(String queueName, String requestId);

    /**
     * Create an in-memory request queue backed by {@link java.util.concurrent.LinkedBlockingQueue}.
     *
     * <p>Suitable for development and testing. Does not survive process restarts.
     *
     * @return a new {@link InMemoryRequestQueue}
     */
    static RequestQueue inMemory() {
        return new InMemoryRequestQueue();
    }
}
