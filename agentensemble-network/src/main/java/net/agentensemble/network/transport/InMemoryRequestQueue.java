package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.WorkRequest;

/**
 * In-memory {@link RequestQueue} backed by {@link LinkedBlockingQueue}.
 *
 * <p>Each queue name maps to an independent blocking queue. Messages are removed on
 * {@link #dequeue}, so {@link #acknowledge} is a no-op.
 *
 * <p>Suitable for development and testing. Does not survive process restarts.
 *
 * <p>Thread-safe: all mutable state is in {@link ConcurrentHashMap} and
 * {@link LinkedBlockingQueue}.
 */
class InMemoryRequestQueue implements RequestQueue {

    private final ConcurrentHashMap<String, LinkedBlockingQueue<WorkRequest>> queues = new ConcurrentHashMap<>();

    @Override
    public void enqueue(String queueName, WorkRequest request) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(request, "request must not be null");
        queues.computeIfAbsent(queueName, k -> new LinkedBlockingQueue<>()).add(request);
    }

    @Override
    public WorkRequest dequeue(String queueName, Duration timeout) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        LinkedBlockingQueue<WorkRequest> queue = queues.computeIfAbsent(queueName, k -> new LinkedBlockingQueue<>());
        try {
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void acknowledge(String queueName, String requestId) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        // No-op for in-memory: messages are removed on dequeue.
    }
}
