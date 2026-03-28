package net.agentensemble.network.transport;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkRequest;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for the cross-ensemble message transport layer.
 *
 * <p>A {@code Transport} abstracts how work requests are delivered between ensembles and how
 * work responses are returned to requesters. Implementations may use in-process queues
 * (development), durable message systems like Redis Streams or Kafka (production), or any
 * custom mechanism.
 *
 * <p>Use the built-in factory for development:
 * <ul>
 *   <li>{@link #websocket()} -- simple mode: in-process queues, no external infrastructure</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Simple mode (default for development)
 * Transport transport = Transport.websocket();
 *
 * // Send a work request
 * transport.send(workRequest);
 *
 * // Receive work (blocking)
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
     * Send a work request to the target ensemble's inbox.
     *
     * <p>The target is determined by the request's {@code task} field or other routing
     * metadata. In simple mode, the request is enqueued in-process.
     *
     * @param request the work request to send; must not be null
     */
    void send(WorkRequest request);

    /**
     * Receive the next work request from this ensemble's inbox.
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
     * Create a simple mode transport backed by in-process queues.
     *
     * <p>No external infrastructure required. Suitable for local development and testing.
     * Does not survive process restarts and does not support horizontal scaling.
     *
     * @return a new {@link SimpleTransport}
     */
    static Transport websocket() {
        return new SimpleTransport();
    }

    /**
     * Close this transport and release any resources.
     *
     * <p>The default implementation is a no-op.
     */
    @Override
    default void close() {}
}
