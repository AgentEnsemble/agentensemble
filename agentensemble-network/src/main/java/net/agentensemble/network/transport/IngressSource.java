package net.agentensemble.network.transport;

import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkRequest;

/**
 * SPI for work request ingress sources.
 *
 * <p>An ingress source produces normalized {@link WorkRequest} objects from an external
 * input channel (HTTP API, message queue, WebSocket, topic subscription). When started,
 * the source pushes incoming work into a sink (typically {@link Transport#send}).
 *
 * <p>Multiple ingress sources can be active simultaneously via {@link IngressCoordinator}.
 *
 * <p>Implementations must be thread-safe.
 *
 * @see IngressCoordinator
 * @see WorkRequest
 */
public interface IngressSource extends AutoCloseable {

    /**
     * Returns a human-readable name for this ingress source (e.g., "http", "queue:kitchen").
     *
     * @return the name; never null
     */
    String name();

    /**
     * Start producing work requests into the given sink.
     *
     * <p>The source should push incoming {@link WorkRequest} objects to the sink. The sink is
     * typically {@link Transport#send} or {@link RequestQueue#enqueue}.
     *
     * @param sink consumer that receives incoming work requests; must not be null
     */
    void start(Consumer<WorkRequest> sink);

    /**
     * Stop this ingress source. Releases resources. Idempotent.
     */
    void stop();

    /**
     * Closes this ingress source by delegating to {@link #stop()}.
     */
    @Override
    default void close() {
        stop();
    }
}
