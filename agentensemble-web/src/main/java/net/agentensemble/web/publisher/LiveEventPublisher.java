package net.agentensemble.web.publisher;

import java.util.function.Consumer;
import net.agentensemble.web.LiveEventSink;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;

/**
 * Producer-side bridge that ships {@link net.agentensemble.web.protocol.LiveEventEnvelope}-wrapped
 * lifecycle events from an in-process {@code WebDashboard} (running in
 * {@code WebDashboard.Mode.PUBLISHER}) to a remote {@code LiveEventHub}.
 *
 * <p>Implements {@link LiveEventSink} so a publisher can be wired into
 * {@link net.agentensemble.web.WebSocketStreamingListener} in place of the local
 * {@code ConnectionManager}. Every call into the sink is wrapped in a
 * {@code LiveEventEnvelope} stamped with this publisher's {@link #info()} and a monotonically
 * increasing sequence number, then handed to the underlying transport.
 *
 * <h2>Review fan-in</h2>
 * <p>Publishers that support a return channel (the WebSocket transport) accept subscriber
 * callbacks via {@link #subscribeToReviewDecisions(Consumer)}. The publisher's
 * {@code RemoteReviewHandler} registers a single subscriber per JVM at construction time so
 * that hub-originated {@link ReviewDecisionForwardMessage}s wake up any blocked review futures.
 * Publishers without a return channel (e.g. HTTP) may either throw
 * {@link UnsupportedOperationException} or silently no-op; in either case the framework rejects
 * a non-no-op {@code ReviewHandler} at build time.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #start()} opens the underlying transport. Idempotent.</li>
 *   <li>{@link #stop()} closes the transport and drains buffered events when the implementation
 *       supports it. Idempotent.</li>
 *   <li>{@link #isConnected()} reports whether the transport is currently up. Transports that
 *       are fire-and-forget (HTTP) may return {@code true} whenever the publisher is started.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe; the streaming listener calls these methods from any
 * number of virtual threads.
 */
public interface LiveEventPublisher extends LiveEventSink, AutoCloseable {

    /**
     * Returns this publisher's identity. The hub keys all per-producer state by
     * {@link ProducerInfo#producerId()}.
     *
     * @return the configured producer info; never null
     */
    ProducerInfo info();

    /**
     * Opens the underlying transport (e.g. connects the WebSocket, primes the HTTP client).
     * Idempotent: subsequent calls after a successful start are no-ops.
     */
    void start();

    /**
     * Closes the underlying transport. Idempotent.
     */
    void stop();

    /**
     * Reports whether the underlying transport is currently up. Transports without a persistent
     * connection (HTTP) may return {@code true} between {@link #start()} and {@link #stop()}.
     *
     * @return true when the transport is ready to deliver envelopes
     */
    boolean isConnected();

    /**
     * Registers a callback to receive review decisions forwarded from the hub. Each
     * publisher supports a single subscriber: the producer-side
     * {@code RemoteReviewHandler}. Calling this method a second time replaces the previous
     * subscriber.
     *
     * <p>Implementations without a return channel (e.g. HTTP-only) may throw
     * {@link UnsupportedOperationException}; the {@code WebDashboard.Builder} rejects pairing
     * such a publisher with a real review handler.
     *
     * @param subscriber the callback to invoke for each {@link ReviewDecisionForwardMessage};
     *                   {@code null} clears the subscriber
     * @throws UnsupportedOperationException when this transport cannot deliver decisions back
     */
    void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber);

    /**
     * Closes the publisher, releasing any underlying resources. Delegates to {@link #stop()}.
     */
    @Override
    default void close() {
        stop();
    }
}
