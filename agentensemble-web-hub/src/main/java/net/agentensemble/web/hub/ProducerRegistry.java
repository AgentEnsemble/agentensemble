package net.agentensemble.web.hub;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.agentensemble.web.ConnectionManager;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concurrent registry of producer state for a {@link LiveEventHub}.
 *
 * <p>The registry creates a per-producer {@link ProducerState} on first observation. State is
 * keyed by {@link ProducerInfo#producerId()}; a producer that disconnects and reconnects with
 * the same ID re-attaches to its retained snapshot. The registry never evicts an active
 * producer; idle producers are evicted by {@link #evictIdle(Duration, BiConsumer)} or when the
 * total count exceeds {@link #maxRetainedProducers}.
 *
 * <p>Thread-safe.
 */
public final class ProducerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProducerRegistry.class);

    private final Map<String, ProducerState> byProducer = new ConcurrentHashMap<>();
    private final MessageSerializer serializer;
    private final int maxRetainedProducers;
    private final int maxRetainedRunsPerProducer;
    private final int maxSnapshotIterationsPerProducer;

    public ProducerRegistry(
            MessageSerializer serializer,
            int maxRetainedProducers,
            int maxRetainedRunsPerProducer,
            int maxSnapshotIterationsPerProducer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        if (maxRetainedProducers < 1) {
            throw new IllegalArgumentException("maxRetainedProducers must be >= 1; got " + maxRetainedProducers);
        }
        if (maxRetainedRunsPerProducer < 1) {
            throw new IllegalArgumentException(
                    "maxRetainedRunsPerProducer must be >= 1; got " + maxRetainedRunsPerProducer);
        }
        if (maxSnapshotIterationsPerProducer < 0) {
            throw new IllegalArgumentException(
                    "maxSnapshotIterationsPerProducer must be >= 0; got " + maxSnapshotIterationsPerProducer);
        }
        this.maxRetainedProducers = maxRetainedProducers;
        this.maxRetainedRunsPerProducer = maxRetainedRunsPerProducer;
        this.maxSnapshotIterationsPerProducer = maxSnapshotIterationsPerProducer;
    }

    /**
     * Returns the {@link ProducerState} for the given producer, creating it on first
     * observation. The {@code info} on the returned state is refreshed to the most recent
     * value (publishers may update tags or version between restarts).
     *
     * @param info the producer info from the most recent envelope; must not be null
     * @return the (possibly newly created) state; never null
     */
    ProducerState getOrCreate(ProducerInfo info) {
        Objects.requireNonNull(info, "info must not be null");
        boolean[] created = {false};
        ProducerState state = byProducer.computeIfAbsent(info.producerId(), id -> {
            ConnectionManager snapshot =
                    new ConnectionManager(serializer, maxRetainedRunsPerProducer, maxSnapshotIterationsPerProducer);
            ProducerState fresh = new ProducerState(info, snapshot);
            if (log.isDebugEnabled()) {
                log.debug("Registered new producer {} ({})", info.producerId(), info.serviceName());
            }
            created[0] = true;
            return fresh;
        });
        state.updateInfo(info);
        state.setActive(true);
        state.markSeen();
        if (created[0]) {
            evictIfOverCap();
        }
        return state;
    }

    /**
     * Marks the named producer inactive. The snapshot state is retained for late-join until the
     * producer is evicted by capacity or idle policy.
     *
     * @param producerId the producer ID; ignored when unknown
     */
    void markInactive(String producerId) {
        ProducerState state = byProducer.get(producerId);
        if (state != null) {
            state.setActive(false);
        }
    }

    /**
     * Evicts inactive producers whose {@code lastSeenAt} is older than {@code idleAfter}. The
     * supplied callback is invoked for each evicted producer so the hub can broadcast a
     * {@link net.agentensemble.web.protocol.ProducerLeftMessage}.
     *
     * @param idleAfter how long a producer must be inactive before eviction; not null
     * @param onEvict   callback (producerId, reason) for every eviction; not null
     */
    public void evictIdle(Duration idleAfter, BiConsumer<String, String> onEvict) {
        Objects.requireNonNull(idleAfter, "idleAfter must not be null");
        Objects.requireNonNull(onEvict, "onEvict must not be null");
        Instant threshold = Instant.now().minus(idleAfter);
        for (Map.Entry<String, ProducerState> e : byProducer.entrySet()) {
            ProducerState state = e.getValue();
            if (!state.isActive() && state.lastSeenAt().isBefore(threshold)) {
                if (byProducer.remove(e.getKey(), state)) {
                    onEvict.accept(e.getKey(), "evicted");
                }
            }
        }
    }

    /**
     * Look up an existing producer state. Returns null when the producer has been evicted or
     * never registered.
     *
     * @param producerId the producer ID; not null
     * @return the state, or null
     */
    ProducerState find(String producerId) {
        return byProducer.get(producerId);
    }

    /**
     * Finds the producer that holds the named pending review ID, or null when none does.
     *
     * @param reviewId the review correlation ID
     * @return the owning producer state, or null
     */
    ProducerState findByPendingReviewId(String reviewId) {
        for (ProducerState state : byProducer.values()) {
            if (state.hasPendingReview(reviewId)) {
                return state;
            }
        }
        return null;
    }

    /**
     * Returns a snapshot list of all currently known producers, sorted by serviceName then
     * producerId for stable browser display.
     *
     * @return immutable list of producer identities
     */
    public List<ProducerInfo> listProducers() {
        List<ProducerInfo> result = new ArrayList<>();
        for (ProducerState state : byProducer.values()) {
            result.add(state.info());
        }
        result.sort(Comparator.comparing((ProducerInfo p) -> p.serviceName() == null ? "" : p.serviceName())
                .thenComparing(ProducerInfo::producerId));
        return List.copyOf(result);
    }

    /**
     * Returns a snapshot map of producerId to state. Iteration order is not specified.
     *
     * @return a defensive copy of the registry's contents
     */
    Map<String, ProducerState> snapshot() {
        return new LinkedHashMap<>(byProducer);
    }

    Collection<ProducerState> states() {
        return byProducer.values();
    }

    private void evictIfOverCap() {
        if (byProducer.size() <= maxRetainedProducers) {
            return;
        }
        // Evict the least-recently-seen inactive producer first. If none are inactive, evict
        // the oldest active producer — this is best-effort under high cardinality and is
        // logged at WARN so deployments notice.
        ProducerState victim = byProducer.values().stream()
                .filter(s -> !s.isActive())
                .min(Comparator.comparing(ProducerState::lastSeenAt))
                .orElseGet(() -> byProducer.values().stream()
                        .min(Comparator.comparing(ProducerState::lastSeenAt))
                        .orElse(null));
        if (victim == null) {
            return;
        }
        if (byProducer.remove(victim.info().producerId(), victim)) {
            log.warn(
                    "ProducerRegistry exceeded maxRetainedProducers={}; evicted oldest producer {}",
                    maxRetainedProducers,
                    victim.info().producerId());
        }
    }
}
