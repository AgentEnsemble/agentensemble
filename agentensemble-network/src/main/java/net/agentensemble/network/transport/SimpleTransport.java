package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Simple mode {@link Transport}: in-process queues for request delivery, in-process store
 * for response delivery.
 *
 * <p>This is the default transport for local development. No external infrastructure is
 * required. Work requests are held in {@link java.util.concurrent.LinkedBlockingQueue}
 * instances; responses are stored in a {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>Created via {@link Transport#websocket()}.
 *
 * <p>Limitations:
 * <ul>
 *   <li>Does not survive process restarts</li>
 *   <li>Does not support horizontal scaling (each JVM has its own queues)</li>
 * </ul>
 *
 * <p>Thread-safe: delegates to thread-safe {@link InMemoryRequestQueue} and
 * {@link InMemoryResultStore}.
 *
 * @see Transport
 */
class SimpleTransport implements Transport {

    private static final Duration DEFAULT_RESULT_TTL = Duration.ofHours(1);

    private final RequestQueue requestQueue;
    private final ResultStore resultStore;

    SimpleTransport() {
        this.requestQueue = RequestQueue.inMemory();
        this.resultStore = ResultStore.inMemory();
    }

    /**
     * Package-private constructor for testing with custom implementations.
     */
    SimpleTransport(RequestQueue requestQueue, ResultStore resultStore) {
        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue must not be null");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    }

    @Override
    public void send(WorkRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requestQueue.enqueue(request.task(), request);
    }

    @Override
    public WorkRequest receive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return requestQueue.dequeue("inbox", timeout);
    }

    @Override
    public void deliver(WorkResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        resultStore.store(response.requestId(), response, DEFAULT_RESULT_TTL);
    }

    /**
     * Returns the underlying request queue (for testing).
     */
    RequestQueue requestQueue() {
        return requestQueue;
    }

    /**
     * Returns the underlying result store (for testing).
     */
    ResultStore resultStore() {
        return resultStore;
    }
}
