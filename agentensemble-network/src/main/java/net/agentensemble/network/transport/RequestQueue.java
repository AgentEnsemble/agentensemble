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
 * <p>Use the built-in factories:
 * <ul>
 *   <li>{@link #inMemory()} -- FIFO in-process queues, single-JVM only</li>
 *   <li>{@link #priority()} -- priority-ordered with aging disabled</li>
 *   <li>{@link #priority(AgingPolicy)} -- priority-ordered with configurable aging</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 *
 * @see Transport
 * @see WorkRequest
 * @see PriorityWorkQueue
 * @see AgingPolicy
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
     * Requests are dequeued in FIFO order regardless of priority.
     *
     * @return a new {@link InMemoryRequestQueue}
     */
    static RequestQueue inMemory() {
        return new InMemoryRequestQueue();
    }

    /**
     * Create a priority work queue with the given aging policy.
     *
     * <p>Requests are dequeued by priority ({@link net.agentensemble.web.protocol.Priority#CRITICAL}
     * first, {@link net.agentensemble.web.protocol.Priority#LOW} last) with FIFO ordering
     * within the same priority. The aging policy promotes unprocessed requests to higher
     * priorities over time, preventing starvation.
     *
     * <p>Suitable for development and testing. Does not survive process restarts.
     *
     * @param agingPolicy the aging configuration; must not be null
     * @return a new {@link PriorityWorkQueue}
     * @see AgingPolicy#every(Duration)
     * @see AgingPolicy#none()
     */
    static PriorityWorkQueue priority(AgingPolicy agingPolicy) {
        return new PriorityWorkQueue(agingPolicy);
    }

    /**
     * Create a priority work queue with aging disabled.
     *
     * <p>Equivalent to {@code priority(AgingPolicy.none())}. Requests are dequeued by
     * priority with FIFO within the same level, but low-priority requests are never
     * promoted.
     *
     * @return a new {@link PriorityWorkQueue} with no aging
     */
    static PriorityWorkQueue priority() {
        return new PriorityWorkQueue();
    }

    /**
     * Create a capacity-bounded priority work queue.
     *
     * <p>When a queue reaches {@code maxCapacity} entries, subsequent
     * {@link #enqueue(String, WorkRequest)} calls throw {@link QueueFullException}.
     *
     * @param agingPolicy the aging configuration; must not be null
     * @param maxCapacity maximum entries per queue name; must be positive
     * @return a new bounded {@link PriorityWorkQueue}
     */
    static PriorityWorkQueue priority(AgingPolicy agingPolicy, int maxCapacity) {
        return new PriorityWorkQueue(
                agingPolicy,
                java.time.Clock.systemUTC(),
                QueueMetrics.noOp(),
                java.time.Duration.ofSeconds(30),
                maxCapacity);
    }
}
