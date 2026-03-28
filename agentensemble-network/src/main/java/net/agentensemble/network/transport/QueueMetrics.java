package net.agentensemble.network.transport;

import net.agentensemble.web.protocol.Priority;

/**
 * Callback interface for reporting queue depth metrics from a {@link PriorityWorkQueue}.
 *
 * <p>Implementations receive queue depth snapshots after each {@link RequestQueue#enqueue}
 * and {@link RequestQueue#dequeue} operation. This enables integration with metrics
 * systems (e.g., Micrometer, Prometheus) without adding a metrics dependency to the
 * network module.
 *
 * <p>Use {@link #noOp()} when metrics collection is not needed:
 *
 * <pre>
 * QueueMetrics metrics = QueueMetrics.noOp();
 * </pre>
 *
 * <p>Or provide a Micrometer-backed implementation:
 *
 * <pre>
 * QueueMetrics metrics = (queueName, priority, depth) -&gt;
 *     Gauge.builder("agentensemble.queue.depth", () -&gt; depth)
 *         .tag("ensemble", queueName)
 *         .tag("priority", priority.name())
 *         .register(registry);
 * </pre>
 *
 * <p>Implementations must be thread-safe. Callbacks are invoked while holding the queue's
 * internal lock, so they should complete quickly and avoid blocking operations.
 *
 * @see PriorityWorkQueue
 */
@FunctionalInterface
public interface QueueMetrics {

    /**
     * Report the current queue depth for a specific priority level.
     *
     * @param queueName the name of the queue (typically the ensemble name)
     * @param priority  the priority level being reported
     * @param depth     the number of requests at this priority level
     */
    void recordQueueDepth(String queueName, Priority priority, int depth);

    /**
     * Create a no-op metrics callback that discards all reports.
     *
     * @return a no-op {@link QueueMetrics} instance
     */
    static QueueMetrics noOp() {
        return (queueName, priority, depth) -> {};
    }
}
