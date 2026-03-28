package net.agentensemble.network.transport;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for the cross-ensemble message transport layer.
 *
 * <p>A {@code Transport} abstracts how work requests are delivered between ensembles and how
 * work responses are returned to requesters. Each transport instance is bound to a specific
 * ensemble (identified by name) and manages that ensemble's inbox. Implementations may use
 * in-process queues (development), durable message systems like Redis Streams or Kafka
 * (production), or any custom mechanism.
 *
 * <p>Use the built-in factories for development:
 * <ul>
 *   <li>{@link #websocket(String)} -- simple mode with an explicit ensemble name</li>
 *   <li>{@link #websocket()} -- simple mode with a default ensemble name</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Simple mode (default for development)
 * Transport transport = Transport.websocket("kitchen");
 *
 * // Send a work request to this transport's inbox
 * transport.send(workRequest);
 *
 * // Receive work from this transport's inbox (blocking)
 * WorkRequest incoming = transport.receive(Duration.ofSeconds(30));
 *
 * // Deliver a response
 * transport.deliver(workResponse);
 * </pre>
 *
 * @see SimpleTransport
 * @see RequestQueue
 * @see ResultStore
 * @see WorkRequest
 * @see WorkResponse
 */
public interface Transport extends AutoCloseable {

    /**
     * Send a work request to this transport's inbox.
     *
     * <p>The request is enqueued for later retrieval via {@link #receive(Duration)}.
     *
     * @param request the work request to send; must not be null
     */
    void send(WorkRequest request);

    /**
     * Receive the next work request from this transport's inbox.
     *
     * <p>Blocks up to the given timeout waiting for a request. Returns {@code null} if no
     * request arrives within the timeout.
     *
     * @param timeout maximum time to wait; must not be null
     * @return the next work request, or {@code null} on timeout
     */
    WorkRequest receive(Duration timeout);

    /**
     * Deliver a work response back to the requester.
     *
     * <p>The response is routed by {@code requestId} to the original requester. In simple
     * mode, the response is stored in-process for retrieval.
     *
     * @param response the work response to deliver; must not be null
     */
    void deliver(WorkResponse response);

    /**
     * Create a simple mode transport backed by in-process queues, bound to the given
     * ensemble name.
     *
     * <p>The ensemble name identifies this transport's inbox. Requests sent via
     * {@link #send(WorkRequest)} are enqueued to this inbox; {@link #receive(Duration)}
     * dequeues from the same inbox.
     *
     * <p>No external infrastructure required. Suitable for local development and testing.
     * Does not survive process restarts and does not support horizontal scaling.
     *
     * @param ensembleName the ensemble name for this transport's inbox; must not be null
     * @return a new {@link SimpleTransport}
     */
    static Transport websocket(String ensembleName) {
        return new SimpleTransport(ensembleName);
    }

    /**
     * Create a simple mode transport with a default ensemble name of {@code "default"}.
     *
     * <p>Convenience factory for single-ensemble development scenarios. Equivalent to
     * {@code Transport.websocket("default")}.
     *
     * @return a new {@link SimpleTransport}
     */
    static Transport websocket() {
        return new SimpleTransport("default");
    }

    /**
     * Create a durable mode transport backed by an external request queue and result store,
     * bound to the given ensemble name.
     *
     * <p>The ensemble name identifies this transport's inbox. Requests sent via
     * {@link #send(WorkRequest)} are enqueued to this inbox; {@link #receive(Duration)}
     * dequeues from the same inbox.
     *
     * <p>Suitable for production deployments with Redis, Kafka, or other durable
     * infrastructure. Survives process restarts and supports horizontal scaling via
     * consumer groups.
     *
     * <p><strong>Usage:</strong>
     * <pre>
     * Transport transport = Transport.durable(
     *     "kitchen",
     *     RedisRequestQueue.create(redisClient),
     *     RedisResultStore.create(redisClient));
     * </pre>
     *
     * @param ensembleName the ensemble name for this transport's inbox; must not be null
     * @param queue        the durable request queue; must not be null
     * @param store        the durable result store; must not be null
     * @return a new {@link Transport}
     */
    static Transport durable(String ensembleName, RequestQueue queue, ResultStore store) {
        return new SimpleTransport(ensembleName, queue, store);
    }

    /**
     * Create a durable mode transport with a default ensemble name of {@code "default"}.
     *
     * <p>Convenience factory equivalent to {@code Transport.durable("default", queue, store)}.
     *
     * @param queue the durable request queue; must not be null
     * @param store the durable result store; must not be null
     * @return a new {@link Transport}
     */
    static Transport durable(RequestQueue queue, ResultStore store) {
        return new SimpleTransport("default", queue, store);
    }

    /**
     * Close this transport and release any resources.
     *
     * <p>The default implementation is a no-op.
     */
    @Override
    default void close() {}
}
