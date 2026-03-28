package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>When a {@link DeliveryRegistry} is provided, responses are routed through the registry
 * based on the {@link DeliverySpec} of the original request. If no registry is configured,
 * responses fall back to direct {@link ResultStore} writes for backward compatibility.
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
 * @see DeliveryRegistry
 */
class SimpleTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(SimpleTransport.class);
    private static final Duration DEFAULT_RESULT_TTL = Duration.ofHours(1);

    private final String ensembleName;
    private final RequestQueue requestQueue;
    private final ResultStore resultStore;
    private final DeliveryRegistry deliveryRegistry;
    private final ConcurrentHashMap<String, DeliverySpec> pendingDeliveries = new ConcurrentHashMap<>();

    SimpleTransport(String ensembleName) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = RequestQueue.inMemory();
        this.resultStore = ResultStore.inMemory();
        this.deliveryRegistry = null;
    }

    /**
     * Package-private constructor for testing with custom implementations.
     */
    SimpleTransport(String ensembleName, RequestQueue requestQueue, ResultStore resultStore) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue must not be null");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
        this.deliveryRegistry = null;
    }

    /**
     * Package-private constructor with pluggable delivery routing.
     *
     * @param ensembleName the ensemble name for this transport's inbox; must not be null
     * @param requestQueue the request queue implementation; must not be null
     * @param resultStore  the result store implementation; must not be null
     * @param registry     the delivery registry for routing responses; must not be null
     */
    SimpleTransport(
            String ensembleName, RequestQueue requestQueue, ResultStore resultStore, DeliveryRegistry registry) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = Objects.requireNonNull(requestQueue, "requestQueue must not be null");
        this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
        this.deliveryRegistry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Create a simple transport with pluggable delivery routing using default in-memory
     * queue and store.
     *
     * @param ensembleName the ensemble name for this transport's inbox; must not be null
     * @param registry     the delivery registry for routing responses; must not be null
     */
    SimpleTransport(String ensembleName, DeliveryRegistry registry) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.requestQueue = RequestQueue.inMemory();
        this.resultStore = ResultStore.inMemory();
        this.deliveryRegistry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public void send(WorkRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requestQueue.enqueue(ensembleName, request);
        if (request.delivery() != null) {
            pendingDeliveries.put(request.requestId(), request.delivery());
        }
    }

    @Override
    public WorkRequest receive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return requestQueue.dequeue(ensembleName, timeout);
    }

    @Override
    public void deliver(WorkResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        DeliverySpec spec = pendingDeliveries.remove(response.requestId());
        if (deliveryRegistry != null && spec != null) {
            deliveryRegistry.deliver(spec, response);
        } else {
            resultStore.store(response.requestId(), response, DEFAULT_RESULT_TTL);
        }
    }

    @Override
    public void close() {
        closeIfAutoCloseable(requestQueue);
        closeIfAutoCloseable(resultStore);
    }

    private static void closeIfAutoCloseable(Object resource) {
        if (resource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn(
                        "Error closing transport resource {}: {}",
                        resource.getClass().getSimpleName(),
                        e.getMessage(),
                        e);
            }
        }
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
