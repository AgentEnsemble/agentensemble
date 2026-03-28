package net.agentensemble.network.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages multiple {@link IngressSource} instances, starting and stopping them together.
 *
 * <p>All sources share a single sink: incoming work from any source is delivered to the
 * same consumer.
 *
 * @see IngressSource
 */
public final class IngressCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngressCoordinator.class);

    private final List<IngressSource> sources;
    private volatile boolean started = false;

    private IngressCoordinator(List<IngressSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * Start all ingress sources, fanning work into the given sink.
     *
     * <p>If already started, this method is a no-op.
     *
     * @param sink consumer that receives incoming work requests; must not be null
     */
    public void startAll(Consumer<WorkRequest> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        if (started) return;
        started = true;
        for (IngressSource source : sources) {
            log.info("Starting ingress source: {}", source.name());
            source.start(sink);
        }
    }

    /**
     * Stop all ingress sources. Idempotent.
     *
     * <p>Exceptions from individual sources are caught and logged so that all sources
     * have a chance to shut down.
     */
    public void stopAll() {
        if (!started) return;
        started = false;
        for (IngressSource source : sources) {
            try {
                source.stop();
            } catch (Exception e) {
                log.warn("Error stopping ingress source '{}': {}", source.name(), e.getMessage());
            }
        }
    }

    /**
     * Returns an unmodifiable view of the registered sources.
     *
     * @return the list of sources; never null
     */
    public List<IngressSource> sources() {
        return sources;
    }

    /**
     * Closes this coordinator by stopping all sources.
     */
    @Override
    public void close() {
        stopAll();
    }

    /**
     * Creates a new builder for constructing an {@link IngressCoordinator}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link IngressCoordinator}.
     */
    public static final class Builder {

        private final List<IngressSource> sources = new ArrayList<>();

        private Builder() {}

        /**
         * Add an ingress source to the coordinator.
         *
         * @param source the source to add; must not be null
         * @return this builder
         */
        public Builder add(IngressSource source) {
            Objects.requireNonNull(source, "source must not be null");
            sources.add(source);
            return this;
        }

        /**
         * Build the coordinator.
         *
         * @return a new {@link IngressCoordinator}
         * @throws IllegalStateException if no sources have been added
         */
        public IngressCoordinator build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("At least one IngressSource is required");
            }
            return new IngressCoordinator(sources);
        }
    }
}
