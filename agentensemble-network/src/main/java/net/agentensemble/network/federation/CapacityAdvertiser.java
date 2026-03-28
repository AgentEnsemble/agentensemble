package net.agentensemble.network.federation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.agentensemble.web.protocol.CapacityUpdateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically broadcasts capacity updates for an ensemble.
 */
public class CapacityAdvertiser implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CapacityAdvertiser.class);

    private final String ensembleName;
    private final String realm;
    private final Supplier<Double> loadSupplier;
    private final int maxConcurrent;
    private final boolean shareable;
    private final Consumer<CapacityUpdateMessage> broadcaster;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public CapacityAdvertiser(
            String ensembleName,
            String realm,
            Supplier<Double> loadSupplier,
            int maxConcurrent,
            boolean shareable,
            Consumer<CapacityUpdateMessage> broadcaster) {
        this.ensembleName = Objects.requireNonNull(ensembleName);
        this.realm = Objects.requireNonNull(realm);
        this.loadSupplier = Objects.requireNonNull(loadSupplier);
        this.maxConcurrent = maxConcurrent;
        this.shareable = shareable;
        this.broadcaster = Objects.requireNonNull(broadcaster);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "capacity-advertiser-" + ensembleName);
            t.setDaemon(true);
            return t;
        });
    }

    /** Start periodic capacity broadcasting at the given interval. */
    public void start(Duration interval) {
        Objects.requireNonNull(interval);
        future = scheduler.scheduleAtFixedRate(this::advertise, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.debug("Capacity advertiser started for ensemble '{}' at {}ms interval", ensembleName, interval.toMillis());
    }

    /** Stop broadcasting and shut down. */
    @Override
    public void close() {
        if (future != null) {
            future.cancel(false);
        }
        scheduler.shutdown();
    }

    private void advertise() {
        try {
            double load = loadSupplier.get();
            String status = load >= 1.0 ? "busy" : "available";
            CapacityUpdateMessage msg =
                    new CapacityUpdateMessage(ensembleName, realm, status, load, maxConcurrent, shareable);
            broadcaster.accept(msg);
        } catch (Exception e) {
            log.warn("Failed to advertise capacity for '{}': {}", ensembleName, e.getMessage());
        }
    }
}
