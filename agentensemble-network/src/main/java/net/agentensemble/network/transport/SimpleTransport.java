package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Simple mode {@link Transport}: in-process queues for request delivery, in-process store
 * for response delivery.
 *
 * <p>Each {@code SimpleTransport} is bound to an ensemble name that identifies its inbox.
 * {@link #send(WorkRequest)} enqueues to this inbox; {@link #receive(Duration)} dequeues
 * from the same inbox. This ensures a consistent send-receive round-trip.
 *
 * <p>This is the default transport for local development. No external infrastructure is
 * required. Work requests are held in {@link java.util.concurrent.LinkedBlockingQueue}
 * instances; responses are stored in a {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p>Created via {@link Transport#websocket(String)} or {@link Transport#websocket()}.
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

    private final String ensembleName;
    private final RequestQueue requestQueue;
    private final ResultStore resultStore;

    SimpleTransport(String ensembleName) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = RequestQueue.inMemory();
        this.resultStore = ResultStore.inMemory();
    }

    /**
     * Package-private constructor for testing with custom implementations.
     */
    SimpleTransport(String ensembleName, RequestQueue requestQueue, ResultStore resultStore) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue must not be null");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    }

    @Override
    public void send(WorkRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requestQueue.enqueue(ensembleName, request);
    }

    @Override
    public WorkRequest receive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return requestQueue.dequeue(ensembleName, timeout);
    }

    @Override
    public void deliver(WorkResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        resultStore.store(response.requestId(), response, DEFAULT_RESULT_TTL);
    }

    /**
     * Returns the ensemble name this transport is bound to.
     */
    String ensembleName() {
        return ensembleName;
    }

    /**
     * Returns the underlying result store (for testing).
     */
    ResultStore resultStore() {
        return resultStore;
    }
}
